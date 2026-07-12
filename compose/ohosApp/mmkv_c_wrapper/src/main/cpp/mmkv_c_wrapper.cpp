#include "./include/mmkv_c_wrapper.h"

#include <MMKV/MMKV.h>

#include <cstdlib>
#include <cstring>
#include <string>

namespace {

MMKVMode mmkvModeFromInt(int mode) {
    return static_cast<MMKVMode>(mode);
}

char *copyStringForKotlin(const std::string &value) {
    const auto length = value.size() + 1;
    auto *result = static_cast<char *>(std::malloc(length));
    if (result == nullptr) {
        return nullptr;
    }
    std::memcpy(result, value.c_str(), length);
    return result;
}

MMKV *mmkvFromHandle(signed long handle) {
    return reinterpret_cast<MMKV *>(handle);
}

} // namespace

signed long mmkv_c_mmkv_with_id(const char *mmap_id, int mode, const char *crypt_key, const char *root_dir,
                                int expected_capacity) {
    if (mmap_id == nullptr || mmap_id[0] == '\0') {
        return 0;
    }

    std::string cryptKeyValue = crypt_key != nullptr ? std::string(crypt_key) : std::string();
    std::string rootDirValue = root_dir != nullptr ? std::string(root_dir) : std::string();
    std::string *cryptKeyPtr = crypt_key != nullptr ? &cryptKeyValue : nullptr;
    std::string *rootDirPtr = root_dir != nullptr ? &rootDirValue : nullptr;

    MMKV *mmkv = MMKV::mmkvWithID(
        mmap_id,
        mmkv::DEFAULT_MMAP_SIZE,
        mmkvModeFromInt(mode),
        cryptKeyPtr,
        rootDirPtr,
        expected_capacity);
    return reinterpret_cast<signed long>(mmkv);
}

int mmkv_c_set_string(signed long handle, const char *key, const char *value) {
    MMKV *mmkv = mmkvFromHandle(handle);
    if (mmkv == nullptr || key == nullptr || value == nullptr) {
        return 0;
    }
    return mmkv->set(std::string(value), key) ? 1 : 0;
}

char *mmkv_c_get_string(signed long handle, const char *key) {
    MMKV *mmkv = mmkvFromHandle(handle);
    if (mmkv == nullptr || key == nullptr) {
        return nullptr;
    }
    std::string value;
    if (!mmkv->getString(key, value)) {
        return nullptr;
    }
    return copyStringForKotlin(value);
}

int mmkv_c_contains_key(signed long handle, const char *key) {
    MMKV *mmkv = mmkvFromHandle(handle);
    if (mmkv == nullptr || key == nullptr) {
        return 0;
    }
    return mmkv->containsKey(key) ? 1 : 0;
}

int mmkv_c_remove_value_for_key(signed long handle, const char *key) {
    MMKV *mmkv = mmkvFromHandle(handle);
    if (mmkv == nullptr || key == nullptr) {
        return 0;
    }
    return mmkv->removeValueForKey(key) ? 1 : 0;
}
