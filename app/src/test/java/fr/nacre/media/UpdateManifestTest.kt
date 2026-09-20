package fr.nacre.media
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class UpdateManifestTest {
    @Test fun selectsTheMatchingAndroidArchitecture() {
        val json = JSONObject("""{"versionCode":24,"versionName":"0.24.0","apk":"https://github.com/default.apk","apks":{"armeabi-v7a":"https://github.com/arm.apk","arm64-v8a":"https://github.com/arm64.apk"}}""")
        assertEquals("https://github.com/arm.apk", parseUpdateManifest(json,"armeabi-v7a")!!.apkUrl)
        assertEquals("https://github.com/arm64.apk", parseUpdateManifest(json,"arm64-v8a")!!.apkUrl)
        assertFalse(updateAvailable(24, parseUpdateManifest(json)))
        assertTrue(updateAvailable(23, parseUpdateManifest(json)))
    }
    @Test fun malformedManifestsNeverOfferAnUpdate() {
        assertNull(parseUpdateManifest(JSONObject("""{"versionCode":0,"apk":"https://github.com/a.apk"}""")))
        assertNull(parseUpdateManifest(JSONObject("""{"versionCode":24,"apk":"javascript:alert(1)"}""")))
    }
}
