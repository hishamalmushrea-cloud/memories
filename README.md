# خريطة الذكريات – Memory Map

تطبيق Android يجمع **خريطة الذكريات + يوميات شخصية + سجل زمني للحياة**.
تحفظ فيه ذكرياتك المهمة، وتكتب يومك بنفسك، ثم تعود بعد سنوات لتجد حياتك مرتبة زمنيًا وجغرافيًا.

> الحالة الحالية: **Phase 1 (الأساس)** و**Phase 2 (المصادقة)** و**Phase 3 (الذكريات)** و**Phase 4 (اليوميات)** و**Phase 5 (الخريطة)** و**Phase 6 (المزامنة)** و**Phase 7 (البحث والتنظيم)** و**Phase 8 (النسخ الاحتياطي)** و**Phase 9 (الأمان والخصوصية)** مكتملة، والبناء + الاختبارات تعمل في CI. بقية المراحل موثقة في [`MemoryMap_Full_Prompt.md`](MemoryMap_Full_Prompt.md).

---

## ماذا يفعل التطبيق

| الوحدة | المعنى |
|---|---|
| **اليوم** | الوحدة الأساسية في اليوميات |
| **الحدث** | الوحدة الأساسية داخل اليوم |
| **الذكرى** | حدث مهم يمكن ربطه باليوم وبالمكان |
| **المكان** | يربط المحتوى بالخريطة |
| **الخط الزمني** | يربط كل شيء بالتاريخ |

التصفح: `اليوم ← الأسبوع ← الشهر ← السنة`، مع ميزة **في مثل هذا اليوم**.

## مبادئ غير قابلة للتفاوض

- **Offline-first**: بعد أول تسجيل دخول، كل شيء يعمل بلا إنترنت.
- **لا ذكاء اصطناعي**: لا تحويل صوت لنص، لا تحليل فيديو، لا توليد نصوص، لا تلخيص. المستخدم هو من يكتب.
- **لا إعلانات، لا Tracking، لا Analytics، لا بيع بيانات.**
- **لا تتبع موقع مستمر**: الموقع يُقرأ عند الطلب فقط.
- **اليوميات `PRIVATE` افتراضيًا**، والمشاركة قرار صريح.
- **البيانات ملكك**: تصدير واستيراد نسخة احتياطية محلية كاملة.

---

## البناء من Android Studio

1. افتح مجلد المشروع في Android Studio (Koala أو أحدث).
2. اترك Gradle يزامن المشروع (يستخدم Gradle Wrapper، فلا حاجة لتنصيب Gradle يدويًا).
3. شغّل:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

أو من سطر الأوامر مباشرة بنفس الأمرين.

### مفاتيح Supabase (اختيارية)

التطبيق يعمل بالكامل بدونها. لربط مشروع Supabase:

```bash
cp local.properties.example local.properties
# ثم ضع SUPABASE_URL و SUPABASE_ANON_KEY داخل local.properties
```

`local.properties` مستثنى من Git. **لا تضع `service_role key` في التطبيق أبدًا** — كل ما داخل الـAPK يعتبر عامًا.

---

## البنية

```text
app/src/main/java/com/memorymap/
├── MemoryMapApp.kt        # Hilt + WorkManager factory + جدولة المزامنة
├── MainActivity.kt        # Compose + Material 3
├── data/
│   ├── local/             # Room: entities, dao, converters, mappers, AppDatabase
│   ├── remote/            # SupabaseClient + إعدادات العميل
│   ├── repository/        # تنفيذات Repository Pattern
│   └── sync/              # SyncWorker (WorkManager)
├── domain/
│   ├── model/             # نماذج نظيفة بلا اعتماد على Android
│   ├── repository/        # الواجهات فقط
│   └── usecase/           # DiaryTime: منطق اليوم/الأسبوع/الشهر/السنة
├── ui/                    # theme + navigation + الشاشات
├── di/                    # Hilt modules
└── util/                  # Geo, MediaStore, MmLog, backup
```

القاعدة: **الـUI لا يلمس Room ولا Supabase مباشرة**، ولا يعرف أي نوع من أنواعهما.

### مستويات SDK (معرّفة في مكان واحد)

```text
minSdk = 26      compileSdk = 36      targetSdk = 36
```

كل الإصدارات مركزية في `gradle/libs.versions.toml`.

## قاعدة البيانات

الجداول: `users`, `memories`, `daily_entries`, `diary_notes`, `media`, `people`, `places`,
`daily_entry_person`, `daily_entry_place`, `memory_person`, `memory_place`, `memory_shares`.

حالات المزامنة (لا يوجد `isSynced: Boolean`):

```text
PENDING_CREATE · PENDING_UPDATE · PENDING_DELETE · SYNCED · SYNC_ERROR
```

الحذف **ناعم**: يبقى الصف كشاهد (`deleted_at`) حتى يؤكد الخادم الحذف، فلا تعود البيانات المحذوفة بعد إعادة الاتصال.

## الاختبارات

```bash
./gradlew testDebugUnitTest
```

الموجود حاليًا:

| الاختبار | ماذا يثبت |
|---|---|
| `DiaryTimeTest` | بداية الأسبوع الأحد، تقسيم الأسابيع/الشهور، «في مثل هذا اليوم» |
| `GeoTest` | حساب المسافة، فلتر «قرب مني» بنطاق 1 كم |
| `BackupPlannerTest` | شكل مجلد النسخة الاحتياطية ورفض صيغة أحدث |
| `MemoryRepositoryImplTest` | كتابة محلية + `PENDING_CREATE`، شاهد الحذف، البحث، الروابط |
| `DiaryDatabaseTest` | تجميع عدّادات اليوم من استعلام Room الحقيقي |
| `DayViewModelTest` | شاشة اليوم تعرض أحداثها وتحفظ مذكرات اليوم |

## التحقق المستمر

`.github/workflows/build.yml` ينفذ على كل push:

```text
verify-dependencies → gradlew help → testDebugUnitTest → lintDebug → assembleDebug
```

ويرفع الـAPK كـartifact. هذه هي الطريقة التي يُتحقق بها من البناء، لأن أي بناء يُدّعى نجاحه يجب أن يكون مبنيًا فعليًا.

عند الفشل ينشر CI تقريرًا كـ**Issue** في المستودع (لأن سجلات GitHub مخزّنة على
نطاق لا يمكن لكل الشبكات الوصول إليه).

> ملاحظة: مخطط Room المُصدَّر (`app/schemas/`) يولّده KSP أثناء البناء، وهو
> مستثنى من Git حاليًا لأن توكن CI في هذا المستودع لا يملك صلاحية الدفع.
> يُفضّل عمل commit له يدويًا مرة واحدة ثم إزالة الاستثناء من `.gitignore`،
> ليصبح أي تغيير في المخطط diff قابلًا للمراجعة.

## الخارطة

- [x] **Phase 1** – Foundation: Gradle, Compose, Theme, Navigation, Room, Supabase foundation, CI
- [x] **Phase 2** – Authentication (Supabase Auth + الجلسة + حساب محلي دون اتصال)
- [x] **Phase 3** – Memories (CRUD + صور/صوت/فيديو)
- [x] **Phase 4** – Diary (اليوم، التقويم، الأسبوع، الشهر، السنة)
- [x] **Phase 5** – Map (مزود خرائط مفتوح + Markers + Clustering)
- [x] **Phase 6** – Sync (طابور غير متصل، رفع، تعارضات)
- [x] **Phase 7** – Search, People, Places, Filters
- [x] **Phase 8** – Backup (Export/Import إلى مجلد يختاره المستخدم)
- [x] **Phase 9** – Security & Privacy (RLS، سياسات Storage، بوابة فحص في CI)
- [ ] **Phase 10** – Testing & Release

## ملفات المشروع

- [البرومبت الكامل](MemoryMap_Full_Prompt.md)
- [CHANGELOG](CHANGELOG.md)
- [سياسة الخصوصية – العربية](docs/legal/PRIVACY_AR.md) · [Privacy Policy (English)](docs/legal/PRIVACY_EN.md)
- [مخطط قاعدة البيانات وسياسات RLS](supabase/schema.sql)
- [التوقيع والنشر](docs/RELEASE.md)

## الرخصة

Apache License 2.0 — انظر [LICENSE](LICENSE).

---

# Memory Map (English)

An Android app that combines a **memory map + a personal diary + a life timeline**.
Offline-first, no AI, no ads, no tracking. Arabic UI by default with English as an
optional locale; all code and comments are in English.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Status: **Phase 1 (Foundation)**, **Phase 2 (Authentication)**, **Phase 3
(Memories)**, **Phase 4 (Diary)**, **Phase 5 (Map)**, **Phase 6 (Sync)**, **Phase 7 (Search)**, **Phase 8 (Backup)** and **Phase 9 (Security)** — project
structure, Gradle version catalog, Compose + Material 3 theme (Cairo/Inter),
navigation shell, the full Room schema with repositories, Supabase email
authentication with Keystore-backed session storage and an offline local account,
and a CI pipeline that builds and tests every push. The full specification lives
in [`MemoryMap_Full_Prompt.md`](MemoryMap_Full_Prompt.md).
