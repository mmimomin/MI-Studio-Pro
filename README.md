# Mi Studio Pro

mi Apk Studio দিয়ে তৈরি Android প্রজেক্ট (WebView)।

- প্যাকেজ: com.miapkstudio.mistudiopro
- শুরুর পেজ: app/src/main/assets/www/index.html
- ওয়েব ফাইল বদলাতে app/src/main/assets/www/ ফোল্ডারের ভেতরের ফাইল বদলান, তারপর আবার Build করুন।

## APK বানানো

### GitHub Actions (সহজ)
1. এই প্রজেক্টের সব ফাইল একটি GitHub Repository-তে আপলোড করুন (.github ফোল্ডারসহ)।
2. Actions ট্যাব -> Build APK -> Run workflow।
3. শেষ হলে Artifacts থেকে app-release.apk নামান।

### Android Studio
File -> Open -> এই ফোল্ডার, তারপর Build -> Build Bundle(s) / APK(s) -> Build APK(s)।

## নিজের কী দিয়ে সাইন
Repository Secrets-এ KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD দিলে workflow সেটি ব্যবহার করবে।
