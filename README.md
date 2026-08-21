# Handpan Numbers (آموزش و تمرین هنگ‌درام بر پایه نت‌نویسی عددی)

اپلیکیشن جامع و آفلاین آموزش، تمرین و سنتز آکوستیک ساز هنگ‌درام (Handpan / Hang Drum) برای سیستم‌عامل اندروید، توسعه‌یافته با کاتلین مدرن و Jetpack Compose.

---

## ۱. ویژگی‌ها و قابلیت‌های محوری

- **سیستم نت‌نویسی عددی یکپارچه:**
  - نت دینگ بم مرکزی: `0` یا `D`
  - فیلدهای صوتی پیرامونی: `1` تا `8` (چیدمان استاندارد متناوب چپ و راست)
  - ضربه اسلپ بدنه: `9` یا `S` (تکنیک ریتمیک شانه ساز)
  - سکوت موسیقایی: `𝄽`
- **موتور سنتز صوتی مدلینگ معین (Modal Synthesizer):**
  - شبیه‌سازی فیزیکی ارتعاش صفحات فلزی با ترکیب هارمونیک‌های ۱ (بسامد پایه)، ۲ (اکتاو غالب)، ۳ (فاصله پنجم) و رزونانس کاواک هلمهولتز دینگ.
  - خروجی استاندارد ۱۶ بیتی PCM در ۴۴.۱ کیلوهرتز با لیمیتر نرم جهت مهار هرگونه اعوجاج و Clipping.
- **تایمینگ دقیق و ایزوله در لایه پیش‌زمینه:**
  - محاسبه بازه ضرب‌ها با ساعت یکنواخت سیستم `System.nanoTime()` به منظور حذف خطای انباشته زمانی (Drift-Free).
  - تفکیک کامل لوپ زمانی صدا از رشته رابط کاربری (UI Thread).
- **تشخیص فرکانس و نت با الگوریتم واقعی YIN:**
  - پیاده‌سازی گام‌به‌گام تابع تفاضلی مربع، تابع نرمال‌شده میانگین تجمعی (CMNDF)، آستانه قطعی و درونیابی سهموی (Parabolic Interpolation).
  - تطبیق نت‌ها بر پایه مقیاس لگاریتمی سنت (Cents Difference: $1200 \times \log_2(f_{actual} / f_{expected})$).
- **ارزیابی زنده نوازندگی با میکروفن (Acoustic Practice Evaluator):**
  - تشخیص ضربات ساز واقعی، ارزیابی زمان‌بندی (عالی، خوب، زود، دیر، نت اشتباه، از دست رفته) و نمایش کارنامه جامع دقت.
- **مربی نردبان سرعت (Speed Ladder Trainer):**
  - افزایش خودکار و تدریجی تمپو (BPM) پس از اتمام موفق تعداد دورهای معین.
- **استودیوی ضبط نمونه صدای ساز کاربر (Custom Sampler Studio):**
  - امکان ضبط و جایگزینی فایل‌های صوتی واقعی ساز کاربر با فایل‌های سنتز پیش‌فرض.
- **دیتابیس آفلاین محلی با Room و JSON Sharing:**
  - ذخیره الگوهای سفارشی، ایمپورت و اکسپورت کدهای متنی الگوها و ثبت تاریخچه پیشرفت تمرین.

---

## ۲. نیازمندی‌های بیلد و محیط توسعه

- **Android Studio:** Ladybug (2024.2.1) یا جدیدتر
- **JDK:** OpenJDK 11 یا OpenJDK 17
- **Gradle:** 8.11.1
- **Android Gradle Plugin (AGP):** 9.1.1
- **Kotlin:** 2.2.10
- **Min SDK:** 24 (Android 7.0)
- **Target / Compile SDK:** 36 (Android 15+)

---

## ۳. دستورات بیلد و اجرای تست‌ها

```bash
# پاکسازی پروژه
gradle clean

# اجرای کلیه تست‌های واحد مهندسی، DSP و زمان‌بندی
gradle :app:testDebugUnitTest

# ساخت نسخه دیباگ APK
gradle :app:assembleDebug
```

---

## ۴. ساختار معماری

```
app/src/main/java/com/example/
├── MainActivity.kt                      // مدیریت کانتینر اصلی و ناوبری
├── audio/
│   ├── AudioEngine.kt                   // مدیریت لودینگ پلی‌فونی و SoundPool
│   ├── HandpanSynthesizer.kt            // سنتز رزونانس صوتی و لیمیتر
│   ├── MetronomeEngine.kt               // موتور مترونوم با تایمینگ Drift-Free
│   ├── PracticeEngine.kt                // موتور اجرای الگو، لوپ و نردبان سرعت
│   ├── YinPitchDetector.kt              // الگوریتم رسمی YIN برای تشخیص فرکانس
│   ├── OnsetAndPitchMatcher.kt          // تشخیص شروع ضربه و مچینگ سنتی
│   ├── AcousticPracticeEvaluator.kt     // ارزیابی زنده ضربات ساز واقعی
│   └── CustomSampleRecorder.kt          // ضبط سمپل‌های آکوستیک کاربر
├── data/
│   ├── local/ (Room Database, Entity, Dao, ShareHelper)
│   ├── builtin/ (BuiltinExercises)
│   └── repository/ (HandpanRepository)
├── model/ (HandpanPattern, NoteEvent, NotePitchConfig, HandpanNote, ...)
└── ui/
    ├── HandpanViewModel.kt
    ├── components/ (HandpanDiscView, NoteTimelineView, Dialogs, ...)
    └── screens/ (HomeScreen, ExerciseLibraryScreen, PracticeScreen, MetronomeScreen, PatternEditorScreen, SettingsScreen)
```
