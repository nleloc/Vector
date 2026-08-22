#include "elf/elf_image.h"

#include <fcntl.h>
#include <linux/xz.h>  // For decompressing .gnu_debugdata
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <link.h>

#include <cstring>
#include <utility>  // For std::move

#include "common/logging.h"

namespace vector::native {

namespace {
// Helper to safely cast an offset from a base pointer.
template <typename T>
inline T PtrOffset(void *base, ptrdiff_t offset) {
    return reinterpret_cast<T>(reinterpret_cast<uintptr_t>(base) + offset);
}
}  // namespace

ElfImage::ElfImage(std::string_view lib_name) : path_(lib_name) {
    if (!findModuleBase()) {
        base_ = nullptr;  // Ensure base_ is null on failure.
        return;
    }

    int fd = open(path_.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        PLOGE("Failed to open ELF file: {}", path_.c_str());
        return;
    }

    struct stat file_info;
    if (fstat(fd, &file_info) < 0) {
        PLOGE("fstat failed for {}", path_.c_str());
        close(fd);
        return;
    }
    file_size_ = file_info.st_size;

    file_map_ = mmap(nullptr, file_size_, PROT_READ, MAP_SHARED, fd, 0);
    close(fd);

    if (file_map_ == MAP_FAILED) {
        PLOGE("mmap failed for {}", path_.c_str());
        file_map_ = nullptr;
        return;
    }

    header_ = static_cast<ElfW(Ehdr) *>(file_map_);
    parseHeaders(header_);

    // Check for and handle compressed debug symbols.
    if (decompressGnuDebugData()) {
        header_debugdata_ = PtrOffset<ElfW(Ehdr) *>(elf_debugdata_.data(), 0);
        // Re-parse to find the .symtab and its .strtab from the debug data.
        parseHeaders(header_debugdata_);
    }
}

ElfImage::~ElfImage() {
    if (file_map_ != nullptr) {
        munmap(file_map_, file_size_);
    }
}

void ElfImage::parseHeaders(ElfW(Ehdr) * header) {
    if (!header) return;

    ElfW(Shdr) *section_headers = PtrOffset<ElfW(Shdr) *>(header, header->e_shoff);
    const char *section_str_table =
        PtrOffset<const char *>(header, section_headers[header->e_shstrndx].sh_offset);

    for (int i = 0; i < header->e_shnum; ++i) {
        ElfW(Shdr) *section_h = &section_headers[i];
        const char *sname = section_str_table + section_h->sh_name;

        switch (section_h->sh_type) {
        case SHT_DYNSYM:
            // We only care about the first .dynsym found in the original ELF file.
            if (dynsym_ == nullptr) {
                dynsym_ = section_h;
                dynsym_start_ = PtrOffset<ElfW(Sym) *>(header, section_h->sh_offset);
            }
            break;
        case SHT_SYMTAB:
            if (strcmp(sname, ".symtab") == 0) {
                symtab_start_ = PtrOffset<ElfW(Sym) *>(header, section_h->sh_offset);
                symtab_count_ = section_h->sh_size / section_h->sh_entsize;
            }
            break;
        case SHT_STRTAB:
            // The string table for .dynsym is usually the first SHT_STRTAB after .dynsym.
            // We identify it by checking if dynsym is found but its strtab is not.
            if (dynsym_ != nullptr && strtab_start_ == nullptr) {
                strtab_start_ = PtrOffset<const char *>(header, section_h->sh_offset);
            }
            // The string table for .symtab is explicitly named ".strtab".
            if (strcmp(sname, ".strtab") == 0) {
                symtab_str_start_ = PtrOffset<const char *>(header, section_h->sh_offset);
            }
            break;
        case SHT_PROGBITS:
            // The load bias is the difference between
            // the virtual address of a loaded segment and its offset in the file.

            // Ensure we skip early sections like .interp or .note
            // by waiting until after dynsym and strtab are found.
            if (dynsym_ == nullptr || strtab_start_ == nullptr) break;

            if (!bias_calculated_ && section_h->sh_flags & SHF_ALLOC && section_h->sh_addr > 0) {
                bias_ = section_h->sh_addr - section_h->sh_offset;
                bias_calculated_ = true;
            }
            break;
        case SHT_HASH:
            // Standard ELF hash table.
            if (nbucket_ == 0) {
                uint32_t *hash_data = PtrOffset<uint32_t *>(header, section_h->sh_offset);
                nbucket_ = hash_data[0];
                // nchain is hash_data[1]
                bucket_ = &hash_data[2];
                chain_ = bucket_ + nbucket_;
            }
            break;
        case SHT_GNU_HASH:
            // GNU-style hash table.
            if (gnu_nbucket_ == 0) {
                uint32_t *hash_data = PtrOffset<uint32_t *>(header, section_h->sh_offset);
                gnu_nbucket_ = hash_data[0];
                gnu_symndx_ = hash_data[1];
                gnu_bloom_size_ = hash_data[2];
                gnu_shift2_ = hash_data[3];
                gnu_bloom_filter_ = reinterpret_cast<uintptr_t *>(&hash_data[4]);
                gnu_bucket_ = reinterpret_cast<uint32_t *>(gnu_bloom_filter_ + gnu_bloom_size_);
                gnu_chain_ = gnu_bucket_ + gnu_nbucket_;
            }
            break;
        }
    }
}

bool ElfImage::decompressGnuDebugData() {
    ElfW(Shdr) *section_headers = PtrOffset<ElfW(Shdr) *>(header_, header_->e_shoff);
    const char *section_str_table =
        PtrOffset<const char *>(header_, section_headers[header_->e_shstrndx].sh_offset);
    ElfW(Off) debugdata_offset = 0;
    ElfW(Off) debugdata_size = 0;

    for (int i = 0; i < header_->e_shnum; ++i) {
        if (strcmp(section_str_table + section_headers[i].sh_name, ".gnu_debugdata") == 0) {
            debugdata_offset = section_headers[i].sh_offset;
            debugdata_size = section_headers[i].sh_size;
            break;
        }
    }

    if (debugdata_offset == 0 || debugdata_size == 0) {
        return false;  // Section not found.
    }
    LOGD("Found .gnu_debugdata section in {} ({} bytes). Decompressing...", path_.c_str(),
         debugdata_size);

    xz_crc32_init();
    struct xz_dec *dec = xz_dec_init(XZ_DYNALLOC, 1 << 26);
    if (!dec) return false;

    struct xz_buf buf;
    buf.in = PtrOffset<const uint8_t *>(header_, debugdata_offset);
    buf.in_pos = 0;
    buf.in_size = debugdata_size;

    elf_debugdata_.resize(debugdata_size * 8);  // Initial guess
    buf.out = reinterpret_cast<uint8_t *>(elf_debugdata_.data());
    buf.out_pos = 0;
    buf.out_size = elf_debugdata_.size();

    while (true) {
        enum xz_ret ret = xz_dec_run(dec, &buf);
        if (ret == XZ_STREAM_END) {
            elf_debugdata_.resize(buf.out_pos);
            xz_dec_end(dec);
            LOGD("Successfully decompressed .gnu_debugdata ({} bytes)", elf_debugdata_.size());
            return true;
        }
        if (ret != XZ_OK) {
            LOGE("XZ decompression failed with code {}", (int)ret);
            xz_dec_end(dec);
            return false;
        }
        if (buf.out_pos == buf.out_size) {
            elf_debugdata_.resize(elf_debugdata_.size() * 2);
            // Reset pointer to the potentially new base address
            buf.out = reinterpret_cast<uint8_t *>(elf_debugdata_.data());
            // Update the total capacity
            buf.out_size = elf_debugdata_.size();
        }
    }
}

ElfW(Addr) ElfImage::getSymbOffset(std::string_view name, uint32_t gnu_hash,
                                   uint32_t elf_hash) const {
    if (auto offset = gnuLookup(name, gnu_hash); offset > 0) {
        return offset;
    } else if (offset = elfLookup(name, elf_hash); offset > 0) {
        return offset;
    } else if (offset = linearLookup(name); offset > 0) {
        return offset;
    } else {
        return 0;
    }
}

ElfW(Addr) ElfImage::gnuLookup(std::string_view name, uint32_t hash) const {
    if (gnu_nbucket_ == 0) return 0;

    constexpr auto bloom_mask_bits = sizeof(ElfW(Addr)) * 8;
    auto bloom_word = gnu_bloom_filter_[(hash / bloom_mask_bits) % gnu_bloom_size_];
    uintptr_t mask =
        (1ULL << (hash % bloom_mask_bits)) | (1ULL << ((hash >> gnu_shift2_) % bloom_mask_bits));

    if ((bloom_word & mask) != mask) {
        return 0;  // Not in bloom filter, definitely not here.
    }

    uint32_t sym_idx = gnu_bucket_[hash % gnu_nbucket_];
    if (sym_idx < gnu_symndx_) return 0;

    do {
        ElfW(Sym) *sym = dynsym_start_ + sym_idx;
        if (((gnu_chain_[sym_idx - gnu_symndx_] ^ hash) >> 1) == 0) {
            if (std::string_view(strtab_start_ + sym->st_name) == name) {
                return sym->st_value;
            }
        }
    } while ((gnu_chain_[sym_idx++ - gnu_symndx_] & 1) == 0);

    return 0;
}

ElfW(Addr) ElfImage::elfLookup(std::string_view name, uint32_t hash) const {
    if (nbucket_ == 0) return 0;

    for (uint32_t n = bucket_[hash % nbucket_]; n != 0; n = chain_[n]) {
        ElfW(Sym) *sym = dynsym_start_ + n;
        if (std::string_view(strtab_start_ + sym->st_name) == name) {
            return sym->st_value;
        }
    }
    return 0;
}

void ElfImage::ensureLinearMapInitialized() const {
    // Lazily parse the .symtab section and build a map for faster lookups.
    if (!symtabs_.empty() || !symtab_start_ || !symtab_str_start_) {
        return;
    }

    for (ElfW(Off) i = 0; i < symtab_count_; ++i) {
        auto *sym = &symtab_start_[i];
        unsigned int st_type = ELF_ST_TYPE(sym->st_info);
        // We only care about function or object symbols that have a size.
        if ((st_type == STT_FUNC || st_type == STT_OBJECT) && sym->st_size > 0) {
            const char *st_name = symtab_str_start_ + sym->st_name;
            symtabs_.emplace(st_name, sym);
        }
    }
}

ElfW(Addr) ElfImage::linearLookup(std::string_view name) const {
    ensureLinearMapInitialized();
    auto it = symtabs_.find(name);
    if (it != symtabs_.end()) {
        return it->second->st_value;
    }
    return 0;
}

std::vector<ElfW(Addr)> ElfImage::linearRangeLookup(std::string_view name) const {
    ensureLinearMapInitialized();
    std::vector<ElfW(Addr)> res;
    for (auto [it, end] = symtabs_.equal_range(name); it != end; ++it) {
        res.emplace_back(it->second->st_value);
    }
    return res;
}

ElfW(Addr) ElfImage::prefixLookupFirst(std::string_view prefix) const {
    ensureLinearMapInitialized();
    // lower_bound finds the first element not less than the prefix.
    auto it = symtabs_.lower_bound(prefix);
    if (it != symtabs_.end() && it->first.starts_with(prefix)) {
        return it->second->st_value;
    }
    return 0;
}

bool ElfImage::findModuleBase() {
    struct DlIterateData {
        std::string target_path;
        uintptr_t base_address = 0;
    } data{
        path_,
        0
    };

    auto callback = [](struct dl_phdr_info *info, size_t, void *data_ptr) -> int {
        auto *data = reinterpret_cast<DlIterateData *>(data_ptr);
        if (info->dlpi_name) {
            std::string_view name(info->dlpi_name);
            if (name.find(data->target_path) != std::string_view::npos) {
                data->base_address = info->dlpi_addr;
                data->target_path = info->dlpi_name;
                return 1;
            }
        }
        return 0;
    };

    dl_iterate_phdr(callback, &data);

    if (data.base_address != 0) {
        base_ = reinterpret_cast<void *>(data.base_address);
        path_ = data.target_path;
        LOGD("Found base for {} at {:#x} via dl_iterate_phdr", path_.c_str(), data.base_address);
        return true;
    }

    LOGE("Fatal: Could not determine a base address for {}", path_.c_str());
    return false;
}

}  // namespace vector::native
