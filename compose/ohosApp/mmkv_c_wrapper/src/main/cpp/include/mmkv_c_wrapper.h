#ifndef MMKV_C_WRAPPER_H
#define MMKV_C_WRAPPER_H

#ifdef __cplusplus
extern "C" {
#endif

// 对应mmkv::MMKVMode的C语言宏定义（需与MMKV原生枚举值一致）
#define MMKV_C_SINGLE_PROCESS 1 << 0 // 单进程模式
#define MMKV_C_MULTI_PROCESS 1 << 1  // 多进程模式

// 对应 mmkv::MMKVLogLevel 的C语言宏定义
#define MMKV_C_LOG_DEBUG 0      // 对应 mmkv::MMKVLogLevel::Debug
#define MMKV_C_LOG_INFO 1       // 对应 mmkv::MMKVLogLevel::Info
#define MMKV_C_LOG_WARNING 2    // 对应 mmkv::MMKVLogLevel::Warning
#define MMKV_C_LOG_ERROR 3      // 对应 mmkv::MMKVLogLevel::Error
#define MMKV_C_LOG_NONE 4       // 对应 mmkv::MMKVLogLevel::None

typedef struct  {
    char **items;
    int size;
} MMKVCStringListReturn;

typedef struct  {
    char *items;
    int size;
} MMKVByteArrayReturn;

// 实例管理
signed long mmkv_c_default_mmkv(int mode, const char *crypt_key);
signed long mmkv_c_mmkv_with_id(const char *mmap_id, int mode,
                                const char *crypt_key, const char *root_dir,
                                int expected_capacity);
void mmkv_c_set_log_level(int log_level);

// 备份所有MMKV实例到指定目录（返回1成功，0失败）
int mmkv_c_backup_one_to_directory(const char *mmap_key, const char *dst_path, const char *src_path);
int mmkv_c_backup_all_to_directory(const char *directory, const char *src_path);

// 基础类型操作（覆盖所有C++支持的类型）
int mmkv_c_set_bool(signed long handle, const char *key, int value);
int mmkv_c_get_bool(signed long handle, const char *key, int default_value);

int mmkv_c_set_int32(signed long handle, const char *key, int value);
int mmkv_c_get_int32(signed long handle, const char *key, int default_value);

int mmkv_c_set_uint32(signed long handle, const char *key, unsigned int value);
unsigned int mmkv_c_get_uint32(signed long handle, const char *key, unsigned int default_value);

int mmkv_c_set_int64(signed long handle, const char *key, signed long value);
signed long mmkv_c_get_int64(signed long handle, const char *key, signed long default_value);

int mmkv_c_set_uint64(signed long handle, const char *key, unsigned long value);
unsigned long mmkv_c_get_uint64(signed long handle, const char *key, unsigned long default_value);

int mmkv_c_set_float(signed long handle, const char *key, float value);
float mmkv_c_get_float(signed long handle, const char *key, float default_value);

int mmkv_c_set_double(signed long handle, const char *key, double value);
double mmkv_c_get_double(signed long handle, const char *key, double default_value);

// 字符串操作
int mmkv_c_set_string(signed long handle, const char *key, const char *value);
char *mmkv_c_get_string(signed long handle, const char *key);

// 字节数组操作
int mmkv_c_set_byte_array(signed long handle, const char *key, char *value, int length);
MMKVByteArrayReturn *mmkv_c_get_byte_array(signed long handle, const char *key);

// 字符串数组操作
int mmkv_c_set_string_array(signed long handle, const char *key, const char *const values[], int length);
MMKVCStringListReturn *mmkv_c_get_string_array(signed long handle, const char *key);

// 通用操作
int mmkv_c_contains_key(signed long handle, const char *key);
int mmkv_c_remove_value_for_key(signed long handle, const char *key);
//int mmkv_c_remove_values_for_keys(signed long handle, const char *const keys[], int length);
void mmkv_c_clear_all(signed long handle);
long mmkv_c_count(signed long handle, int filter_expire);
long mmkv_c_total_size(signed long handle);
long mmkv_c_actual_size(signed long handle);

// 高级功能（加密、过期时间）
// 加密与实例管理
int mmkv_c_rekey(signed long handle, const char *new_key);
void mmkv_c_check_reset_crypt_key(signed long handle, const char *new_key);

int mmkv_c_enable_auto_expire(signed long handle, unsigned int expired_seconds);

const char *mmkv_c_mmapID(signed long handle);
MMKVCStringListReturn *mmkv_c_all_keys(signed long handle);

void mmkv_c_clear_memory_cache(signed long handle);
void mmkv_c_sync(signed long handle);

void mmkv_c_close(signed long handle);
void mmkv_c_trim(signed long handle);

// 检查内容变更（无返回值）
void mmkv_c_check_content_changed(signed long handle);

// typedef void (*ContentChangeHandler)(const char* mmap_id);
//
// void mmkv_c_register_content_change_handler(signed long handle, ContentChangeHandler handler);
// void mmkv_c_unregister_content_change_handler(signed long handle);


#ifdef __cplusplus
}
#endif
#endif // MMKV_C_WRAPPER_H