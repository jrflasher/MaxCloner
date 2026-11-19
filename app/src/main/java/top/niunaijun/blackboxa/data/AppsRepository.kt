package top.niunaijun.blackboxa.data

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import android.webkit.URLUtil
import androidx.lifecycle.MutableLiveData
import java.io.File
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.utils.AbiUtils
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.bean.InstalledAppBean
import top.niunaijun.blackboxa.util.MemoryManager
import top.niunaijun.blackboxa.util.CloneNameStore
import top.niunaijun.blackboxa.util.MicroGInstaller
import top.niunaijun.blackboxa.util.getString


class AppsRepository {
    val TAG: String = "AppsRepository"
    private var mInstalledList = mutableListOf<AppInfo>()

    
    private fun safeLoadAppLabel(applicationInfo: ApplicationInfo): String {
        return try {
            BlackBoxCore.getPackageManager().getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load label for ${applicationInfo.packageName}: ${e.message}")
            applicationInfo.packageName 
        }
    }

    
    private fun safeLoadAppIcon(
            applicationInfo: ApplicationInfo
    ): android.graphics.drawable.Drawable? {
        return try {
            
            if (MemoryManager.shouldSkipIconLoading()) {
                Log.w(
                        TAG,
                        "Memory usage high (${MemoryManager.getMemoryUsagePercentage()}%), skipping icon for ${applicationInfo.packageName}"
                )
                return null
            }

            val icon = BlackBoxCore.getPackageManager().getApplicationIcon(applicationInfo)

            
            if (icon is android.graphics.drawable.BitmapDrawable) {
                val bitmap = icon.bitmap
                
                if (bitmap.width > 96 || bitmap.height > 96) {
                    try {
                        val scaledBitmap =
                                android.graphics.Bitmap.createScaledBitmap(bitmap, 96, 96, true)
                        android.graphics.drawable.BitmapDrawable(
                                BlackBoxCore.getPackageManager()
                                        .getResourcesForApplication(applicationInfo.packageName),
                                scaledBitmap
                        )
                    } catch (e: Exception) {
                        Log.w(
                                TAG,
                                "Failed to scale icon for ${applicationInfo.packageName}: ${e.message}"
                        )
                        icon
                    }
                } else {
                    icon
                }
            } else {
                icon
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load icon for ${applicationInfo.packageName}: ${e.message}")
            null 
        }
    }

    fun previewInstallList() {
        try {
            synchronized(mInstalledList) {
                val installedApplications: List<ApplicationInfo> =
                        BlackBoxCore.getPackageManager().getInstalledApplications(0)
                val installedList = mutableListOf<AppInfo>()

                for (installedApplication in installedApplications) {
                    try {
                        val file = File(installedApplication.sourceDir)

                        if ((installedApplication.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                                continue

                        if (!AbiUtils.isSupport(file)) continue

                        
                        if (BlackBoxCore.get().isBlackBoxApp(installedApplication.packageName)) {
                            Log.d(
                                    TAG,
                                    "Filtering out BlackBox app: ${installedApplication.packageName}"
                            )
                            continue
                        }

                        val isXpModule = false

                        val info =
                                AppInfo(
                                        safeLoadAppLabel(installedApplication),
                                        safeLoadAppIcon(
                                                installedApplication
                                        ), 
                                        installedApplication.packageName,
                                        installedApplication.sourceDir,
                                        isXpModule
                                )
                        installedList.add(info)
                    } catch (e: Exception) {
                        Log.e(
                                TAG,
                                "Error processing app ${installedApplication.packageName}: ${e.message}"
                        )
                    }
                }
                this.mInstalledList.clear()
                this.mInstalledList.addAll(installedList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in previewInstallList: ${e.message}")
        }
    }

    fun getInstalledAppList(
            userID: Int,
            loadingLiveData: MutableLiveData<Boolean>,
            appsLiveData: MutableLiveData<List<InstalledAppBean>>
    ) {
        try {
            loadingLiveData.postValue(true)
            synchronized(mInstalledList) {
                val blackBoxCore = BlackBoxCore.get()
                Log.d(TAG, mInstalledList.joinToString(","))
                val newInstalledList =
                        mInstalledList.map {
                            val installedAnywhere = if (userID == -1) {
                                try { blackBoxCore.users.any { u -> blackBoxCore.isInstalled(it.packageName, u.id) } } catch (e: Exception) { false }
                            } else {
                                blackBoxCore.isInstalled(it.packageName, userID)
                            }
                            InstalledAppBean(
                                    it.name,
                                    it.icon,
                                    it.packageName,
                                    it.sourceDir,
                                    installedAnywhere
                            )
                        }
                appsLiveData.postValue(newInstalledList)
                loadingLiveData.postValue(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getInstalledAppList: ${e.message}")
            loadingLiveData.postValue(false)
            appsLiveData.postValue(emptyList())
        }
    }

    fun getVmInstallList(userId: Int, appsLiveData: MutableLiveData<List<AppInfo>>) {
        // Satu halaman: userId -1 = gabungkan semua clone dari semua user/space.
        if (userId == -1) {
            getVmInstallListAll(appsLiveData)
            return
        }
        try {
            
            if (MemoryManager.isMemoryCritical()) {
                Log.w(
                        TAG,
                        "Memory critical (${MemoryManager.getMemoryUsagePercentage()}%), forcing garbage collection"
                )
                MemoryManager.forceGarbageCollectionIfNeeded()
            }

            val blackBoxCore = BlackBoxCore.get()

            
            val users = blackBoxCore.users
            Log.d(TAG, "getVmInstallList: userId=$userId, total users=${users.size}")
            users.forEach { user -> Log.d(TAG, "User: id=${user.id}, name=${user.name}") }

            val sortListData = AppManager.mRemarkSharedPreferences.getString("AppList$userId", "")
            val sortList = sortListData?.split(",")

            
            var applicationList: List<ApplicationInfo>? = null
            var retryCount = 0
            val maxRetries = 3

            while (applicationList == null && retryCount < maxRetries) {
                try {
                    applicationList = blackBoxCore.getInstalledApplications(0, userId)
                    if (applicationList == null) {
                        Log.w(
                                TAG,
                                "getVmInstallList: Attempt ${retryCount + 1} returned null, retrying..."
                        )
                        retryCount++
                        Thread.sleep(100) 
                    }
                } catch (e: Exception) {
                    Log.e(
                            TAG,
                            "getVmInstallList: Error getting applications on attempt ${retryCount + 1}: ${e.message}"
                    )
                    retryCount++
                    if (retryCount < maxRetries) {
                        Thread.sleep(200) 
                    }
                }
            }

            
            if (applicationList == null) {
                Log.e(
                        TAG,
                        "getVmInstallList: applicationList is null for userId=$userId after $maxRetries attempts"
                )
                appsLiveData.postValue(emptyList())
                return
            }

            
            Log.d(
                    TAG,
                    "getVmInstallList: userId=$userId, applicationList.size=${applicationList.size}"
            )
            if (applicationList.isNotEmpty()) {
                Log.d(TAG, "First app: ${applicationList.first().packageName}")
            } else {
                Log.w(TAG, "getVmInstallList: No applications found for userId=$userId")
            }

            val appInfoList = mutableListOf<AppInfo>()

            
            val sortedApplicationList =
                    if (!sortList.isNullOrEmpty()) {
                        try {
                            applicationList.sortedWith(AppsSortComparator(sortList))
                        } catch (e: Exception) {
                            Log.e(TAG, "getVmInstallList: Error sorting applications: ${e.message}")
                            applicationList 
                        }
                    } else {
                        applicationList
                    }

            
            sortedApplicationList.forEachIndexed { index, applicationInfo ->
                try {
                    
                    if (index > 0 && index % 25 == 0) {
                        if (MemoryManager.isMemoryCritical()) {
                            Log.w(TAG, "Memory critical during processing, forcing GC")
                            MemoryManager.forceGarbageCollectionIfNeeded()
                        }
                    }

                    
                    if (applicationInfo == null) {
                        Log.w(
                                TAG,
                                "getVmInstallList: Skipping null applicationInfo at index $index"
                        )
                        return@forEachIndexed
                    }

                    
                    if (applicationInfo.packageName.isNullOrBlank()) {
                        Log.w(
                                TAG,
                                "getVmInstallList: Skipping app with null/blank package name at index $index"
                        )
                        return@forEachIndexed
                    }

                    // Sembunyikan paket infra Google (MicroG, GSF, Play Store) dari daftar clone.
                    if (MicroGInstaller.HIDDEN_SYSTEM_PACKAGES.contains(applicationInfo.packageName)) {
                        return@forEachIndexed
                    }

                    val label = safeLoadAppLabel(applicationInfo)
                    val info =
                            AppInfo(
                                    CloneNameStore.getName(userId, applicationInfo.packageName, label),
                                    safeLoadAppIcon(
                                            applicationInfo
                                    ),
                                    applicationInfo.packageName,
                                    applicationInfo.sourceDir ?: "",
                                    false,
                                    userId
                            )

                    appInfoList.add(info)

                    
                    if (index > 0 && index % 50 == 0) {
                        Log.d(
                                TAG,
                                "getVmInstallList: Processed $index/${sortedApplicationList.size} apps - ${MemoryManager.getMemoryInfo()}"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(
                            TAG,
                            "getVmInstallList: Error processing app at index $index (${applicationInfo?.packageName}): ${e.message}"
                    )
                    
                }
            }

            Log.d(
                    TAG,
                    "getVmInstallList: processed ${appInfoList.size} apps - ${MemoryManager.getMemoryInfo()}"
            )

            
            
            if (appInfoList.isEmpty()) {
                Log.d(
                        TAG,
                        "getVmInstallList: No virtual apps found for userId=$userId, showing empty list (correct for new users)"
                )
            } else {
                Log.d(
                        TAG,
                        "getVmInstallList: Showing ${appInfoList.size} virtual apps for userId=$userId"
                )
            }

            
            try {
                appsLiveData.postValue(appInfoList)
            } catch (e: Exception) {
                Log.e(TAG, "getVmInstallList: Error posting to LiveData: ${e.message}")
                
                try {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            appsLiveData.postValue(appInfoList)
                        } catch (e2: Exception) {
                            Log.e(
                                    TAG,
                                    "getVmInstallList: Fallback posting also failed: ${e2.message}"
                            )
                        }
                    }
                } catch (e3: Exception) {
                    Log.e(
                            TAG,
                            "getVmInstallList: Could not schedule fallback posting: ${e3.message}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getVmInstallList: ${e.message}")
            try {
                appsLiveData.postValue(emptyList())
            } catch (e2: Exception) {
                Log.e(TAG, "getVmInstallList: Error posting empty list: ${e2.message}")
            }
        }
    }

    private fun getVmInstallListAll(appsLiveData: MutableLiveData<List<AppInfo>>) {
        try {
            val blackBoxCore = BlackBoxCore.get()
            val all = mutableListOf<AppInfo>()
            val users = try { blackBoxCore.users } catch (e: Exception) { emptyList() }
            for (user in users) {
                val list = try { blackBoxCore.getInstalledApplications(0, user.id) } catch (e: Exception) { null } ?: continue
                for (app in list) {
                    if (app == null || app.packageName.isNullOrBlank()) continue
                    if (MicroGInstaller.HIDDEN_SYSTEM_PACKAGES.contains(app.packageName)) continue
                    val label = safeLoadAppLabel(app)
                    val displayName = CloneNameStore.getName(user.id, app.packageName, label)
                    all.add(AppInfo(displayName, safeLoadAppIcon(app), app.packageName, app.sourceDir ?: "", false, user.id))
                }
            }
            appsLiveData.postValue(all)
        } catch (e: Exception) {
            Log.e(TAG, "Error in getVmInstallListAll: ${e.message}")
            try { appsLiveData.postValue(emptyList()) } catch (e2: Exception) { }
        }
    }

    /** Clone dari halaman utama: pilih space kosong dulu, kalau app sudah
     *  ada di semua space, buat space/user baru — jadi 1 app bisa punya
     *  banyak clone, masing-masing dengan nama berbeda, dalam 1 halaman. */
    fun installApkAuto(source: String, resultLiveData: MutableLiveData<String>) {
        try {
            val blackBoxCore = BlackBoxCore.get()
            val packageName = resolvePackageName(source)
            if (packageName == null) {
                resultLiveData.postValue("Installation failed: could not read package name")
                return
            }
            val users = try { blackBoxCore.users.toList() } catch (e: Exception) { emptyList() }
            var targetUserId = -1
            var existingCount = 0
            for (user in users) {
                val installed = try { blackBoxCore.isInstalled(packageName, user.id) } catch (e: Exception) { false }
                if (installed) existingCount++ else if (targetUserId == -1) targetUserId = user.id
            }
            if (targetUserId == -1) {
                val newId = (users.maxOfOrNull { it.id } ?: -1) + 1
                try {
                    blackBoxCore.createUser(newId)
                    // Ruang baru langsung dapat MicroG aktif di background, tanpa install manual.
                    MicroGInstaller.installToUser(newId) { }
                } catch (e: Exception) { Log.e(TAG, "createUser failed: ${e.message}") }
                targetUserId = newId
            }
            val originalName = resolveAppLabel(source, packageName)
            // Simpan nama default dulu ("WhatsApp", "WhatsApp 2", ...) sebelum install,
            // supaya saat list di-refresh nama clone baru langsung berbeda.
            if (!CloneNameStore.hasName(targetUserId, packageName)) {
                CloneNameStore.setName(targetUserId, packageName, CloneNameStore.defaultNameForClone(originalName, existingCount))
            }
            installApk(source, targetUserId, resultLiveData)
        } catch (e: Exception) {
            Log.e(TAG, "Error in installApkAuto: ${e.message}")
            resultLiveData.postValue("Installation failed: ${e.message}")
        }
    }

    private fun resolvePackageName(source: String): String? {
        return try {
            val file = File(source)
            if (file.exists()) {
                BlackBoxCore.getPackageManager().getPackageArchiveInfo(source, 0)?.packageName
            } else if (source.startsWith("content://") || source.startsWith("file://")) {
                null
            } else {
                // ListActivity mengirim packageName langsung untuk app terinstall.
                source.trim().takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) { null }
    }

    private fun resolveAppLabel(source: String, packageName: String): String {
        return try {
            val file = File(source)
            if (file.exists()) {
                val info = BlackBoxCore.getPackageManager().getPackageArchiveInfo(source, 0)
                if (info != null) {
                    try { info.applicationInfo?.let { BlackBoxCore.getPackageManager().getApplicationLabel(it).toString() } ?: packageName } catch (e: Exception) { packageName }
                } else packageName
            } else packageName
        } catch (e: Exception) { packageName }
    }

    fun installApk(source: String, userId: Int, resultLiveData: MutableLiveData<String>) {
        try {
            
            if (source.contains("blackbox") ||
                            source.contains("niunaijun") ||
                            source.contains("vspace") ||
                            source.contains("virtual")
            ) {
                
                try {
                    val blackBoxCore = BlackBoxCore.get()
                    val hostPackageName = BlackBoxCore.getHostPkg()

                    
                    if (!URLUtil.isValidUrl(source)) {
                        val file = File(source)
                        if (file.exists()) {
                            val packageInfo =
                                    BlackBoxCore.getPackageManager()
                                            .getPackageArchiveInfo(source, 0)
                            if (packageInfo != null && packageInfo.packageName == hostPackageName) {
                                resultLiveData.postValue(
                                        "Cannot install BlackBox app from within BlackBox. This would create infinite recursion and is not allowed for security reasons."
                                )
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not verify if this is BlackBox app: ${e.message}")
                }
            }

            val blackBoxCore = BlackBoxCore.get()
            val installResult =
                    if (URLUtil.isValidUrl(source)) {
                        val uri = Uri.parse(source)
                        blackBoxCore.installPackageAsUser(uri, userId)
                    } else {
                        blackBoxCore.installPackageAsUser(source, userId)
                    }

            if (installResult.success) {
                updateAppSortList(userId, installResult.packageName, true)
                resultLiveData.postValue(getString(R.string.install_success))
            } else {
                resultLiveData.postValue(getString(R.string.install_fail, installResult.msg))
            }
            scanUser()
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK: ${e.message}")
            resultLiveData.postValue("Installation failed: ${e.message}")
        }
    }

    fun unInstall(packageName: String, userID: Int, resultLiveData: MutableLiveData<String>) {
        try {
            CloneNameStore.removeName(userID, packageName)
            BlackBoxCore.get().uninstallPackageAsUser(packageName, userID)
            updateAppSortList(userID, packageName, false)
            scanUser()
            resultLiveData.postValue(getString(R.string.uninstall_success))
        } catch (e: Exception) {
            Log.e(TAG, "Error uninstalling APK: ${e.message}")
            resultLiveData.postValue("Uninstallation failed: ${e.message}")
        }
    }

    fun launchApk(packageName: String, userId: Int, launchLiveData: MutableLiveData<Boolean>) {
        try {
            val result = BlackBoxCore.get().launchApk(packageName, userId)
            launchLiveData.postValue(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching APK: ${e.message}")
            launchLiveData.postValue(false)
        }
    }

    fun clearApkData(packageName: String, userID: Int, resultLiveData: MutableLiveData<String>) {
        try {
            BlackBoxCore.get().clearPackage(packageName, userID)
            resultLiveData.postValue(getString(R.string.clear_success))
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing APK data: ${e.message}")
            resultLiveData.postValue("Clear failed: ${e.message}")
        }
    }

    
    private fun scanUser() {
        try {
            val blackBoxCore = BlackBoxCore.get()
            val userList = blackBoxCore.users

            if (userList.isEmpty()) {
                return
            }

            val id = userList.last().id

            if (blackBoxCore.getInstalledApplications(0, id).isEmpty()) {
                blackBoxCore.deleteUser(id)
                AppManager.mRemarkSharedPreferences.edit().apply {
                    remove("Remark$id")
                    remove("AppList$id")
                    apply()
                }
                CloneNameStore.removeNamesForUser(id)
                scanUser()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in scanUser: ${e.message}")
        }
    }

    
    private fun updateAppSortList(userID: Int, pkg: String, isAdd: Boolean) {
        try {
            val savedSortList = AppManager.mRemarkSharedPreferences.getString("AppList$userID", "")

            val sortList = linkedSetOf<String>()
            if (savedSortList != null) {
                sortList.addAll(savedSortList.split(","))
            }

            if (isAdd) {
                sortList.add(pkg)
            } else {
                sortList.remove(pkg)
            }

            AppManager.mRemarkSharedPreferences.edit().apply {
                putString("AppList$userID", sortList.joinToString(","))
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating app sort list: ${e.message}")
        }
    }

    
    fun updateApkOrder(userID: Int, dataList: List<AppInfo>) {
        if (userID == -1) return
        try {
            AppManager.mRemarkSharedPreferences.edit().apply {
                putString("AppList$userID", dataList.joinToString(",") { it.packageName })
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating APK order: ${e.message}")
        }
    }
}
