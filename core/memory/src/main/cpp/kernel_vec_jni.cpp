#include <jni.h>
#include <android/log.h>
#include "sqlite3.h"
#include "sqlite-vec.h"
#include <cstring>
#include <cstdlib>

#define LOG_TAG "KernelVec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static bool initialized = false;

static void ensure_vec_registered() {
    if (!initialized) {
        int rc = sqlite3_auto_extension((void(*)(void)) sqlite3_vec_init);
        if (rc == SQLITE_OK) {
            LOGI("sqlite-vec %s registered", SQLITE_VEC_VERSION);
        } else {
            LOGE("sqlite3_auto_extension failed: %d", rc);
        }
        initialized = true;
    }
}

// ── getVersion ──────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_kernel_ai_core_memory_vector_VectorStoreJni_getVersion(JNIEnv* env, jobject) {
    ensure_vec_registered();
    return env->NewStringUTF(SQLITE_VEC_VERSION);
}

// ── openDatabase ─────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jlong JNICALL
Java_com_kernel_ai_core_memory_vector_VectorStoreJni_openDatabase(
        JNIEnv* env, jobject, jstring jpath) {
    ensure_vec_registered();
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    sqlite3* db = nullptr;
    int rc = sqlite3_open(path, &db);
    env->ReleaseStringUTFChars(jpath, path);
    if (rc != SQLITE_OK) {
        LOGE("sqlite3_open failed: %s", sqlite3_errmsg(db));
        sqlite3_close(db);
        return 0L;
    }
    sqlite3_exec(db, "PRAGMA journal_mode=WAL", nullptr, nullptr, nullptr);
    return reinterpret_cast<jlong>(db);
}

// ── closeDatabase ────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_kernel_ai_core_memory_vector_VectorStoreJni_closeDatabase(
        JNIEnv*, jobject, jlong handle) {
    auto* db = reinterpret_cast<sqlite3*>(handle);
    if (db) sqlite3_close(db);
}

// ── exec ─────────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_kernel_ai_core_memory_vector_VectorStoreJni_exec(
        JNIEnv* env, jobject, jlong handle, jstring jsql) {
    auto* db = reinterpret_cast<sqlite3*>(handle);
    const char* sql = env->GetStringUTFChars(jsql, nullptr);
    char* errmsg = nullptr;
    int rc = sqlite3_exec(db, sql, nullptr, nullptr, &errmsg);
    env->ReleaseStringUTFChars(jsql, sql);
    if (rc != SQLITE_OK) {
        jstring jerr = env->NewStringUTF(errmsg ? errmsg : "unknown error");
        sqlite3_free(errmsg);
        return jerr;
    }
    return nullptr; // null == success
}

// ── upsert ───────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_kernel_ai_core_memory_vector_VectorStoreJni_upsert(
        JNIEnv* env, jobject, jlong handle, jstring jtable, jlong rowId, jfloatArray jembedding) {
    auto* db = reinterpret_cast<sqlite3*>(handle);
    const char* table = env->GetStringUTFChars(jtable, nullptr);

    jsize dims = env->GetArrayLength(jembedding);
    jfloat* floats = env->GetFloatArrayElements(jembedding, nullptr);

    char sql[256];
    snprintf(sql, sizeof(sql),
             "INSERT OR REPLACE INTO %s(rowid, embedding) VALUES (?, ?)", table);

    sqlite3_stmt* stmt = nullptr;
    int rc = sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr);
    if (rc == SQLITE_OK) {
        sqlite3_bind_int64(stmt, 1, rowId);
        sqlite3_bind_blob(stmt, 2, floats, dims * sizeof(float), SQLITE_STATIC);
        rc = sqlite3_step(stmt);
        sqlite3_finalize(stmt);
    }

    env->ReleaseFloatArrayElements(jembedding, floats, JNI_ABORT);
    env->ReleaseStringUTFChars(jtable, table);

    if (rc != SQLITE_DONE) {
        return env->NewStringUTF(sqlite3_errmsg(db));
    }
    return nullptr;
}

// ── delete ───────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_kernel_ai_core_memory_vector_VectorStoreJni_deleteRow(
        JNIEnv* env, jobject, jlong handle, jstring jtable, jlong rowId) {
    auto* db = reinterpret_cast<sqlite3*>(handle);
    const char* table = env->GetStringUTFChars(jtable, nullptr);

    char sql[256];
    snprintf(sql, sizeof(sql), "DELETE FROM %s WHERE rowid = ?", table);

    sqlite3_stmt* stmt = nullptr;
    int rc = sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr);
    if (rc == SQLITE_OK) {
        sqlite3_bind_int64(stmt, 1, rowId);
        rc = sqlite3_step(stmt);
        sqlite3_finalize(stmt);
    }
    env->ReleaseStringUTFChars(jtable, table);

    if (rc != SQLITE_DONE) {
        return env->NewStringUTF(sqlite3_errmsg(db));
    }
    return nullptr;
}

// ── search ───────────────────────────────────────────────────────────────────
// Returns a flat double[] of [rowId0, dist0, rowId1, dist1, ...] pairs.
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_kernel_ai_core_memory_vector_VectorStoreJni_search(
        JNIEnv* env, jobject, jlong handle, jstring jtable, jfloatArray jquery, jint k) {
    auto* db = reinterpret_cast<sqlite3*>(handle);
    const char* table = env->GetStringUTFChars(jtable, nullptr);

    jsize dims = env->GetArrayLength(jquery);
    jfloat* queryFloats = env->GetFloatArrayElements(jquery, nullptr);

    char sql[256];
    snprintf(sql, sizeof(sql),
             "SELECT rowid, distance FROM %s WHERE embedding MATCH ? AND k = ?", table);

    sqlite3_stmt* stmt = nullptr;
    jdoubleArray result = env->NewDoubleArray(0);

    int rc = sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr);
    if (rc == SQLITE_OK) {
        sqlite3_bind_blob(stmt, 1, queryFloats, dims * sizeof(float), SQLITE_STATIC);
        sqlite3_bind_int(stmt, 2, k);

        double rows[200]; // max k=100
        int count = 0;
        while (sqlite3_step(stmt) == SQLITE_ROW && count < k) {
            rows[count * 2]     = static_cast<double>(sqlite3_column_int64(stmt, 0));
            rows[count * 2 + 1] = sqlite3_column_double(stmt, 1);
            count++;
        }
        sqlite3_finalize(stmt);

        result = env->NewDoubleArray(count * 2);
        if (count > 0) {
            env->SetDoubleArrayRegion(result, 0, count * 2, rows);
        }
    }

    env->ReleaseFloatArrayElements(jquery, queryFloats, JNI_ABORT);
    env->ReleaseStringUTFChars(jtable, table);
    return result;
}
