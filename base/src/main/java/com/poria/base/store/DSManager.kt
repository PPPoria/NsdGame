package com.poria.base.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val PREFERENCES_FILE_NAME = "app_preferences"

// DataStore 的委托必须只创建一次，放在文件级私有扩展上，避免每 new 一次就多一个实例。
private val Context.appDataStore: DataStore<Preferences> by
    preferencesDataStore(name = PREFERENCES_FILE_NAME)

/**
 * 轻量的键值存储（基于 DataStore Preferences）。
 *
 * 使用前提：[init] 已被调用。继承 [com.poria.base.BaseApp] 的宿主会自动完成初始化。
 *
 * 几个约定：
 * - 所有 `getXxx` 返回的 Flow 都做了 `distinctUntilChanged`，无关 key 变化不会触发下游刷新。
 * - `Double` 用 long 位模式存储（DataStore 没有 double 类型），不会丢精度。
 * - 频繁写入（每帧级别）的数据不要放这里，DataStore 每次 edit 都会重写整个文件。
 */
object DSManager {

    @Volatile
    private var _store: DataStore<Preferences>? = null

    private val store: DataStore<Preferences>
        get() = checkNotNull(_store) {
            "DSManager 尚未初始化：让宿主 Application 继承 BaseApp，或在 Application.onCreate 里调用 DSManager.init(this)"
        }

    /** 幂等初始化，重复调用不会创建第二个 DataStore 实例。 */
    fun init(context: Context) {
        if (_store == null) {
            _store = context.applicationContext.appDataStore
        }
    }

    /** 读文件失败（例如文件损坏）时回退到空配置，而不是让整个 UI 崩掉。 */
    private val safeData: Flow<Preferences>
        get() = store.data.catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }

    // region Boolean

    suspend fun setBoolean(key: String, value: Boolean) {
        store.edit { it[booleanPreferencesKey(key)] = value }
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Flow<Boolean> =
        safeData.map { it[booleanPreferencesKey(key)] ?: defaultValue }.distinctUntilChanged()

    // endregion

    // region Int

    suspend fun setInt(key: String, value: Int) {
        store.edit { it[intPreferencesKey(key)] = value }
    }

    fun getInt(key: String, defaultValue: Int = 0): Flow<Int> =
        safeData.map { it[intPreferencesKey(key)] ?: defaultValue }.distinctUntilChanged()

    // endregion

    // region Long

    suspend fun setLong(key: String, value: Long) {
        store.edit { it[longPreferencesKey(key)] = value }
    }

    fun getLong(key: String, defaultValue: Long = 0L): Flow<Long> =
        safeData.map { it[longPreferencesKey(key)] ?: defaultValue }.distinctUntilChanged()

    // endregion

    // region Float

    suspend fun setFloat(key: String, value: Float) {
        store.edit { it[floatPreferencesKey(key)] = value }
    }

    fun getFloat(key: String, defaultValue: Float = 0f): Flow<Float> =
        safeData.map { it[floatPreferencesKey(key)] ?: defaultValue }.distinctUntilChanged()

    // endregion

    // region Double

    /**
     * Double 存成 long 位模式（`toRawBits`），读出来用 `Double.fromBits` 还原，精度无损。
     * 注意：同一个 key 不要既当 Long 又当 Double 用，它们共用 `longPreferencesKey`。
     */
    suspend fun setDouble(key: String, value: Double) {
        store.edit { it[longPreferencesKey(key)] = value.toRawBits() }
    }

    fun getDouble(key: String, defaultValue: Double = 0.0): Flow<Double> =
        safeData.map { prefs ->
            prefs[longPreferencesKey(key)]?.let(Double::fromBits) ?: defaultValue
        }.distinctUntilChanged()

    // endregion

    // region String

    suspend fun setString(key: String, value: String) {
        store.edit { it[stringPreferencesKey(key)] = value }
    }

    fun getString(key: String, defaultValue: String = ""): Flow<String> =
        safeData.map { it[stringPreferencesKey(key)] ?: defaultValue }.distinctUntilChanged()

    // endregion

    /**
     * 删除某个 key。同一个名字可能以不同类型写入过，这里全部清掉。
     */
    suspend fun remove(key: String) {
        store.edit { prefs ->
            listOf<Preferences.Key<*>>(
                booleanPreferencesKey(key),
                intPreferencesKey(key),
                longPreferencesKey(key),
                floatPreferencesKey(key),
                stringPreferencesKey(key)
            ).forEach { typedKey -> prefs.remove(typedKey) }
        }
    }

    /** 清空所有配置（"恢复默认设置"用）。 */
    suspend fun clear() {
        store.edit { prefs ->
            prefs.asMap().keys.toList().forEach { typedKey -> prefs.remove(typedKey) }
        }
    }
}
