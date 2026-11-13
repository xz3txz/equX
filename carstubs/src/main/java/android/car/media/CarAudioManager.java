package android.car.media;

/**
 * Compile-only stub OEM-расширения CarAudioManager (android.car.jar системы ГУ).
 *
 * Подключается в :app как compileOnly и в APK не попадает: в рантайме классы
 * берутся из системы. Нужен только чтобы унаследоваться от абстрактного
 * {@link CarCustomSettingCallback} и {@link CarVolumeCallback} — Proxy для абстрактного класса не работает.
 */
public class CarAudioManager {

    public abstract static class CarCustomSettingCallback {
        public void onEnableChanged(int zoneId, int param, boolean enable) {
        }

        public void onValueChanged(int zoneId, int param, int value) {
        }
    }

    public abstract static class CarVolumeCallback {
        public void onGroupVolumeChanged(int zoneId, int groupId, int flags) {
        }

        public void onMasterMuteChanged(int zoneId, int flags) {
        }
    }
}
