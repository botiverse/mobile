#include "./include/mmkv_c_wrapper.h"

#include <MMKV/MMBuffer.h>
#include <MMKV/MMKV.h>

#include <cstring>
#include <vector>

static char *stringToChar(const std::string &ptr) {
    // get the memory in the C++ class and place it 'C like' for Java to read
    // and recycle.
    auto len = ptr.size() + 1;
    auto rtn = (char *)malloc(len);

    if (rtn == nullptr) {
        return nullptr;
    }

    memcpy(rtn, ptr.c_str(), len);
    return rtn;
}

// 内部工具：转换MMKVMode
static MMKVMode c_mode_to_cpp(int mode) { return static_cast<MMKVMode>(mode); }

signed long mmkv_c_default_mmkv(int mode, const char *crypt_key) {
    std::string *key_ptr = nullptr;
    if (crypt_key != nullptr) {
        std::string crypt_key_string(crypt_key);
        key_ptr = &crypt_key_string;
    }

    MMKV *mmkv = MMKV::defaultMMKV(c_mode_to_cpp(mode), key_ptr);
    return reinterpret_cast<signed long>(mmkv);
}

signed long mmkv_c_mmkv_with_id(const char *mmap_id, int mode, const char *crypt_key, const char *root_dir,
                                int expected_capacity) {
    std::string *key_ptr = nullptr;
    if (crypt_key != nullptr) {
        std::string crypt_key_string(crypt_key);
        key_ptr = &crypt_key_string;
    }
    std::string *root_dir_ptr = nullptr;
    if (root_dir != nullptr) {
        std::string root_dir_string(root_dir);
        root_dir_ptr = &root_dir_string;
    }
    MMKV *mmkv = MMKV::mmkvWithID(mmap_id, mmkv::DEFAULT_MMAP_SIZE, c_mode_to_cpp(mode), key_ptr, root_dir_ptr,
                                  expected_capacity);
    return reinterpret_cast<signed long>(mmkv);
}

// 设置日志级别（MMKV原生日志级别需匹配）
void mmkv_c_set_log_level(int log_level) { MMKV::setLogLevel(static_cast<MMKVLogLevel>(log_level)); }

int mmkv_c_backup_one_to_directory(const char *mmap_key, const char *dst_path, const char *src_path) {
    // 参数有效性检查（避免空指针）
    if (!mmap_key) {
        return 0;
    }

    // 转换C字符串为C++类型
    std::string cpp_src_path(src_path);
    return MMKV::backupOneToDirectory(std::string(mmap_key), std::string(dst_path), &cpp_src_path) ? 1 : 0;
}

// 备份所有实例到目录
int mmkv_c_backup_all_to_directory(const char *directory, const char *src_path) {
    if (!directory)
        return 0;
    std::string cpp_src_path(src_path);
    return MMKV::backupAllToDirectory(std::string(directory), &cpp_src_path) ? 1 : 0;
}


int mmkv_c_set_bool(signed long handle, const char *key, int value) {
    auto *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? (mmkv->set(static_cast<bool>(value), key) ? 1 : 0) : 0;
}

int mmkv_c_get_bool(signed long handle, const char *key, int default_value) {
    auto *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? mmkv->getBool(key, default_value != 0) : default_value;
}

int mmkv_c_set_int32(signed long handle, const char *key, int value) {
    auto *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? (mmkv->set(value, key) ? 1 : 0) : 0;
}

int mmkv_c_get_int32(signed long handle, const char *key, int default_value) {
    auto *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? mmkv->getInt32(key, default_value) : default_value;
}

int mmkv_c_set_uint32(signed long handle, const char *key, unsigned int value) {
    auto *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? (mmkv->set(value, key) ? 1 : 0) : 0;
}

unsigned int mmkv_c_get_uint32(signed long handle, const char *key, unsigned int default_value) {
    auto *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? mmkv->getUInt32(key, default_value) : default_value;
}

int mmkv_c_set_int64(signed long handle, const char *key, int64_t value) {
    auto *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? (mmkv->set(value, key) ? 1 : 0) : 0;
}

int64_t mmkv_c_get_int64(signed long handle, const char *key, int64_t default_value) {
    auto *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? mmkv->getInt64(key, default_value) : default_value;
}

int mmkv_c_set_uint64(signed long handle, const char *key, uint64_t value) { 
    auto *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? (mmkv->set(value, key) ? 1 : 0) : 0;
 }

uint64_t mmkv_c_get_uint64(signed long handle, const char *key, uint64_t default_value) { 
    auto *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? mmkv->getUInt64(key, default_value) : default_value;
 }

int mmkv_c_set_float(signed long handle, const char *key, float value) {
    auto *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? (mmkv->set(value, key) ? 1 : 0) : 0;
}

float mmkv_c_get_float(signed long handle, const char *key, float default_value) {
    auto *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? mmkv->getFloat(key, default_value) : default_value;
}

int mmkv_c_set_double(signed long handle, const char *key, double value) {
    auto *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? (mmkv->set(value, key) ? 1 : 0) : 0;
}

double mmkv_c_get_double(signed long handle, const char *key, double default_value) {
    auto *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? mmkv->getDouble(key, default_value) : default_value;
}

int mmkv_c_set_byte_array(signed long handle, const char *key, char *value, int length) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    if (!mmkv)
        return 0;
    auto buffer = mmkv::MMBuffer(value, length, mmkv::MMBufferNoCopy);
    return mmkv->set(buffer, key) ? 1 : 0;
}

MMKVByteArrayReturn *mmkv_c_get_byte_array(signed long handle, const char *key) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    if (!mmkv)
        return nullptr;

    auto buffer = mmkv->getBytes(key);
    if(buffer.length() == 0) {
        return nullptr;
    }
    
    auto rtn = (MMKVByteArrayReturn *)malloc(sizeof(MMKVByteArrayReturn));
    if (rtn == nullptr) {
        return nullptr;
    }
    
    rtn->size = buffer.length();
    rtn->items = (char *)malloc(buffer.length());
    memcpy(rtn->items, buffer.getPtr(), buffer.length());
    return rtn;
}

int mmkv_c_set_string_array(signed long handle, const char *key, const char *const values[], int length) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    if (!mmkv)
        return 0;

    std::vector<std::string> vector;

    for (size_t i = 0; i < length; i++) {
        std::string tmp(values[i], strlen(values[i]));
        vector.push_back(tmp);
    }

    return mmkv->set(vector, key) ? 1 : 0;
}

// 字符串数组获取（动态分配二维数组）
MMKVCStringListReturn *mmkv_c_get_string_array(signed long handle, const char *key) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    if (!mmkv) {
        return nullptr;
    }

    std::vector<std::string> vector;
    auto result = mmkv->getVector(key, vector);
    if(!result) {
        return nullptr;
    }
    
    auto rtn = (MMKVCStringListReturn *)malloc(sizeof(MMKVCStringListReturn));
    if (rtn == nullptr) {
        return nullptr;
    }

    rtn->size = 0;
    rtn->items = (char **)malloc(sizeof(char **) * vector.size());
    if (rtn->items == nullptr) {
        return nullptr;
    }
    for (std::string str : vector) {
        (rtn->items)[rtn->size] = stringToChar(str);
        (rtn->size) += 1;
    }
    return rtn;
}

int mmkv_c_set_string(signed long handle, const char *key, const char *value) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? (mmkv->set(std::string(value), key) ? 1 : 0) : 0;
}

char *mmkv_c_get_string(signed long handle, const char *key) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    if (!mmkv)
        return nullptr;
    std::string value;
    if (!mmkv->getString(key, value))
        return nullptr;

    return stringToChar(value);
}

int mmkv_c_contains_key(signed long handle, const char *key) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? (mmkv->containsKey(key) ? 1 : 0) : 0;
}

int mmkv_c_remove_value_for_key(signed long handle, const char *key) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? (mmkv->removeValueForKey(key) ? 1 : 0) : 0;
}

//int mmkv_c_remove_values_for_keys(signed long handle, const char *const keys[], int length) {
//    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
//    if (mmkv) {
//        return 0;
//    }
//
//    std::vector<std::string> vector;
//
//    for (size_t i = 0; i < length; i++) {
//        std::string tmp(keys[i], strlen(keys[i]));
//        vector.push_back(tmp);
//    }
//    printf("mmkvtest count=%zu", vector.size());
//    
//    return mmkv->removeValuesForKeys(vector) ? 1 : 0;
//}

void mmkv_c_clear_all(signed long handle) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    if (mmkv)
        mmkv->clearAll();
}

long mmkv_c_count(signed long handle, int filter_expire) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? mmkv->count(filter_expire == 1) : 0;
}

long mmkv_c_total_size(signed long handle) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? mmkv->totalSize() : 0;
}

long mmkv_c_actual_size(signed long handle) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? mmkv->actualSize() : 0;
}

const char *mmkv_c_mmapID(signed long handle) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? mmkv->mmapID().c_str() : nullptr;
}

MMKVCStringListReturn *mmkv_c_all_keys(signed long handle) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    if (!mmkv)
        return nullptr;

    std::vector<std::string> vector = mmkv->allKeys();

    auto rtn = (MMKVCStringListReturn *)malloc(sizeof(MMKVCStringListReturn));

    if (rtn == nullptr) {
        return nullptr;
    }

    rtn->size = 0;
    rtn->items = (char **)malloc(sizeof(char **) * vector.size());
    if (rtn->items == nullptr) {
        return nullptr;
    }
    for (std::string str : vector) {
        (rtn->items)[rtn->size] = stringToChar(str);
        (rtn->size) += 1;
    }
    return rtn;
}

// 清除内存缓存
void mmkv_c_clear_memory_cache(signed long handle) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    if (mmkv)
        mmkv->clearMemoryCache();
}

// 同步数据到磁盘
void mmkv_c_sync(signed long handle) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    if (mmkv)
        mmkv->sync();
}

// 关闭实例（根据MMKV生命周期管理需求）
void mmkv_c_close(signed long handle) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    if (mmkv)
        mmkv->close();
}

// 裁剪存储空间
void mmkv_c_trim(signed long handle) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    if (mmkv)
        mmkv->trim();
}


void mmkv_c_check_content_changed(signed long handle) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    if (mmkv) {
        mmkv->checkContentChanged();
    }
}

//// 内容变更回调管理（使用静态变量存储回调）
// static ContentChangeHandler g_content_handler = nullptr;
//
// void mmkv_c_register_content_change_handler(signed long handle, ContentChangeHandler handler) {
//     MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
//     if (mmkv) {
//         g_content_handler = handler;
//         // 注册C++层回调（需MMKV支持自定义回调）
//         mmkv->registerContentChangeHandler([](const std::string &mmapID) {
//             if (g_content_handler) {
//                 g_content_handler(mmapID.c_str());
//             }
//         });
//     }
// }
//
// void mmkv_c_unregister_content_change_handler(signed long handle) {
//     MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
//     if (mmkv) {
//         mmkv->unRegisterContentChangeHandler();
//         g_content_handler = nullptr;
//     }
// }


// 加密功能：重新设置密钥
int mmkv_c_rekey(signed long handle, const char *new_key) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? (mmkv->reKey(new_key) ? 1 : 0) : 0;
}

// 启用自动过期时间
int mmkv_c_enable_auto_expire(signed long handle, unsigned int expired_seconds) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    return mmkv ? (mmkv->enableAutoKeyExpire(expired_seconds) ? 1 : 0) : 0;
}

// 重新设置加密密钥
void mmkv_c_check_reset_crypt_key(signed long handle, const char *new_key) {
    MMKV *mmkv = reinterpret_cast<MMKV *>(handle);
    if (mmkv) {
        std::string newKeyStr(new_key);
        mmkv->checkReSetCryptKey(&newKeyStr);
    }
}
