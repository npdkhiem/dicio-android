package org.stypox.dicio.io.input.android_stt

/**
 * One installed [android.speech.RecognitionService].
 * [serviceName] is the class name from PackageManager (it may start with '.').
 */
data class InstalledRecognizer(
    val packageName: String,
    val serviceName: String,
    val label: String,
)

/**
 * Picks a speech recognizer without touching PackageManager.
 *
 * Dicio's own package is always excluded, including suffixes such as
 * `org.stypox.dicio.main`, so recognition cannot loop back into Vosk.
 * An empty [userChoice] means Automatic: Google Speech Recognition & Synthesis
 * (`com.google.android.tts`), then the Google app, then the system default,
 * then the first remaining service.
 */
object RecognizerChooser {
    const val AUTOMATIC = ""
    const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
    const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
    const val DICIO_PACKAGE_PREFIX = "org.stypox.dicio"

    fun isDicioPackage(packageName: String): Boolean {
        return packageName.startsWith(DICIO_PACKAGE_PREFIX)
    }

    /** True for Dicio packages and for [ownPackage] when the app id is renamed. */
    fun isExcluded(packageName: String, ownPackage: String? = null): Boolean {
        if (isDicioPackage(packageName)) return true
        return !ownPackage.isNullOrEmpty() && packageName == ownPackage
    }

    /** `package/class`, with a leading '.' on [serviceName] expanded. */
    fun componentFlat(packageName: String, serviceName: String): String {
        val cls = if (serviceName.startsWith(".")) packageName + serviceName else serviceName
        return "$packageName/$cls"
    }

    fun matchesComponent(flat: String, packageName: String, serviceName: String): Boolean {
        val wanted = flat.trim()
        if (wanted.isEmpty()) return false
        val full = componentFlat(packageName, serviceName)
        if (wanted == full) return true
        val slash = full.indexOf('/')
        if (slash <= 0) return false
        val pkg = full.substring(0, slash)
        val cls = full.substring(slash + 1)
        // Settings.Secure sometimes stores package/.ClassName.
        if (cls.startsWith("$pkg.")) {
            val short = "$pkg/${cls.removePrefix(pkg)}"
            if (wanted == short) return true
        }
        return false
    }

    /**
     * @param userChoice empty for Automatic, otherwise a flattened component name
     * @param systemDefault Settings.Secure "voice_recognition_service", or null if it
     * cannot be read
     * @return null when every installed service is Dicio's or the list is empty
     */
    fun choose(
        userChoice: String,
        installed: List<InstalledRecognizer>,
        systemDefault: String?,
        ownPackage: String? = null,
    ): InstalledRecognizer? {
        val usable = installed.filter { !isExcluded(it.packageName, ownPackage) }
        if (usable.isEmpty()) return null

        val choice = userChoice.trim()
        if (choice.isNotEmpty()) {
            usable.firstOrNull { matchesComponent(choice, it.packageName, it.serviceName) }
                ?.let { return it }
        }
        return chooseAutomatic(usable, systemDefault)
    }

    private fun chooseAutomatic(
        usable: List<InstalledRecognizer>,
        systemDefault: String?,
    ): InstalledRecognizer? {
        preferPackage(usable, GOOGLE_TTS_PACKAGE)?.let { return it }
        preferPackage(usable, GOOGLE_APP_PACKAGE)?.let { return it }
        val fallback = systemDefault?.trim().orEmpty()
        if (fallback.isNotEmpty()) {
            usable.firstOrNull { matchesComponent(fallback, it.packageName, it.serviceName) }
                ?.let { return it }
        }
        return usable.firstOrNull()
    }

    /**
     * Prefer a recognition service inside [packageName]. Google's speech
     * synthesis package exposes GoogleTTSRecognitionService, which does not
     * end in SpeechRecognitionService, so a class ending in RecognitionService
     * is the next choice. Anything else in that package is last.
     */
    private fun preferPackage(
        usable: List<InstalledRecognizer>,
        packageName: String,
    ): InstalledRecognizer? {
        val inPackage = usable.filter { it.packageName == packageName }
        if (inPackage.isEmpty()) return null
        return inPackage.firstOrNull { it.serviceName.endsWith("SpeechRecognitionService") }
            ?: inPackage.firstOrNull { it.serviceName.endsWith("RecognitionService") }
            ?: inPackage.first()
    }
}
