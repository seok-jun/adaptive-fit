const fs = require('node:fs');
const path = require('node:path');
const {
  withAndroidManifest, withAppBuildGradle, withDangerousMod, withMainApplication,
} = require('expo/config-plugins');

function replaceOnce(source, before, after) {
  if (!source.includes(before)) throw new Error('Unexpected Android template: ' + before);
  return source.replace(before, after);
}

module.exports = function withPersonalAndroid(config) {
  config = withAndroidManifest(config, mod => {
    const attrs = mod.modResults.manifest.application[0].$;
    attrs['android:allowBackup'] = 'false';
    attrs['android:fullBackupContent'] = '@xml/fit_backup';
    attrs['android:dataExtractionRules'] = '@xml/fit_transfer';
    return mod;
  });
  config = withMainApplication(config, mod => {
    const setup = 'com.facebook.react.modules.network.OkHttpClientProvider.setOkHttpClientFactory { FitTransport.client() }';
    if (!mod.modResults.contents.includes(setup)) {
      mod.modResults.contents = replaceOnce(mod.modResults.contents, 'super.onCreate()', 'super.onCreate()\n    ' + setup);
    }
    return mod;
  });
  config = withAppBuildGradle(config, mod => {
    let src = mod.modResults.contents;
    if (!src.includes('FIT_SIGN_FILE')) {
      src = replaceOnce(src, 'signingConfigs {', `signingConfigs {
        personal {
            if (System.getenv("FIT_SIGN_FILE")) {
                storeFile file(System.getenv("FIT_SIGN_FILE"))
                storePassword System.getenv("FIT_SIGN_PASSWORD")
                keyAlias "adaptive-fit"
                keyPassword System.getenv("FIT_SIGN_PASSWORD")
            }
        }`);
      const release = src.indexOf('        release {');
      if (release < 0) throw new Error('Android release config missing');
      src = src.slice(0, release) + replaceOnce(src.slice(release), 'signingConfig signingConfigs.debug', 'signingConfig signingConfigs.personal');
      src += '\ndependencies { testImplementation "junit:junit:4.13.2"; testImplementation "com.squareup.okhttp3:mockwebserver:4.12.0" }\n';
    }
    mod.modResults.contents = src;
    return mod;
  });
  return withDangerousMod(config, ['android', async mod => {
    const base = path.join(mod.modRequest.platformProjectRoot, 'app/src');
    for (const [file, dest] of Object.entries({
      'FitTransport.kt': 'main/java/com/adaptivefit/personal',
      'FitTransportTest.kt': 'test/java/com/adaptivefit/personal',
      'fit_backup.xml': 'main/res/xml',
      'fit_transfer.xml': 'main/res/xml',
    })) {
      const dir = path.join(base, dest);
      fs.mkdirSync(dir, {recursive: true});
      fs.copyFileSync(path.join(__dirname, 'android', file), path.join(dir, file));
    }
    return mod;
  }]);
};
