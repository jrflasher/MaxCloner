package top.niunaijun.blackboxa.util

import top.niunaijun.blackboxa.app.AppManager

/**
 * Nama custom per clone. Satu halaman menampilkan semua clone, jadi tiap
 * clone (userId + packageName) boleh punya nama berbeda, misal
 * "WhatsApp", "WhatsApp 2", "WhatsApp Kerja".
 * Disimpan di SharedPreferences yang sama dengan remark user.
 */
object CloneNameStore {
    private fun key(userId: Int, packageName: String) = "CloneName_${userId}_$packageName"

    fun getName(userId: Int, packageName: String, defaultName: String): String {
        return try {
            val saved = AppManager.mRemarkSharedPreferences.getString(key(userId, packageName), null)
            if (saved.isNullOrBlank()) defaultName else saved
        } catch (e: Exception) { defaultName }
    }

    fun hasName(userId: Int, packageName: String): Boolean {
        return try {
            !AppManager.mRemarkSharedPreferences.getString(key(userId, packageName), null).isNullOrBlank()
        } catch (e: Exception) { false }
    }

    fun setName(userId: Int, packageName: String, name: String) {
        try {
            AppManager.mRemarkSharedPreferences.edit().putString(key(userId, packageName), name).apply()
        } catch (e: Exception) { }
    }

    fun removeName(userId: Int, packageName: String) {
        try {
            AppManager.mRemarkSharedPreferences.edit().remove(key(userId, packageName)).apply()
        } catch (e: Exception) { }
    }

    fun removeNamesForUser(userId: Int) {
        try {
            val prefs = AppManager.mRemarkSharedPreferences
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith("CloneName_${userId}_") }.forEach { editor.remove(it) }
            editor.apply()
        } catch (e: Exception) { }
    }

    /** Nama default untuk clone ke-n dari package yang sama: "WhatsApp", "WhatsApp 2", ... */
    fun defaultNameForClone(originalName: String, existingCloneCount: Int): String {
        return if (existingCloneCount <= 0) originalName else "$originalName ${existingCloneCount + 1}"
    }
}
