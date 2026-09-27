# مطابقة المواصفة — أين نُفّذ كل بند، وبماذا أُثبت

هذا المستند يجيب سؤالًا واحدًا: **هل يطابق هذا المستودع `MemoryMap_Full_Prompt.md`؟**
لكل قسم في البرومبت: أين نُفّذ في الكود، وبأي شيء أُثبت. وعمود «الأثبات» يعني: اختبار
باسمه، أو فحص في CI، أو ملف يمكن فتحه الآن. وما لم يُثبت مكتوب صراحةً كذلك.

المرجع الزمني: آخر تشغيل أخضر على الفرع — **480 اختبارًا في 52 صنفًا، 0 فشل، 0 خطأ،
0 متجاوز؛ lint ‏0 أخطاء و25 تنبيهًا كلها إعلانات نسخ أحدث**.

| # | البند في البرومبت | أين نُفّذ | الأثبات |
|---|---|---|---|
| 1 | تطبيق Android حقيقي لخريطة الذكريات واليوميات والخط الزمني | `app/src/main/java/com/memorymap/` (128 ملف Kotlin)، `app/src/test/` (53 ملف اختبار)، `README.md` | بناء debug و release و AAB في CI، وشهادة `sha256` للأقراص في تقرير كل تشغيل |
| 2 | Android + Kotlin + Compose + Material 3، بلا Flutter ولا NDK ولا ذكاء اصطناعي ولا إعلانات | `gradle/libs.versions.toml` (لا مكتبة تحليل أو إعلان)، `app/build.gradle.kts` | `ci/check-security.sh` + `ClientSecurityTest` (7) — يفشل عند ظهور أي منها |
| 3 | Offline-first: كل شيء يعمل بلا شبكة | Room مصدر الحقيقة، طابور المزامنة، `SERVICE_LIMITS.md` §3 | `MemoryRepositoryImplTest`, `DeletedMemorySyncTest`, `MediaSyncTest` (21) |
| 4 | `minSdk 26` / `compileSdk 36` / `targetSdk 36` من كتالوج مركزي | `app/build.gradle.kts` + `Sdk` في الكتالوج | وصفة APK في CI تُبنى بهذه القيم؛ وقرار الخروج عنها غير موجود |
| 5 | المعمارية `UI / Domain / Data / Local / Remote / Sync`، والواجهة لا تلمس Room | الحزم تحت `com.memorymap`، والحقن في `RepositoryModule` (15 `@Binds` + 4 `@Provides`) | `grep` على `ui/` لا يجد أي `dao` أو `MemoryMapDatabase` |
| 6 | خريطة مفتوحة خلف `MapProvider`، تجميع، اختيار يدوي، إسناد، تخزين مؤقت | `ui/map/` (`SlippyMap`, `MapScreen`, `LocationPickerScreen`), `data/map/TileServerMapProvider.kt` | `TileServerMapProviderTest` (9) و`MapClusteringTest` (11) و`WebMercatorTest` (13) |
| 7–10 | الشاشة الرئيسية، الذكريات، إضافة ذكرى، تفاصيلها | `ui/MemoryMapRoot.kt` (5 تبويبات + `QuickAddSheet`), `ui/memories/` | `MemoriesViewModelTest`, `MemoryEditorViewModelTest` (11), `MemoryRepositoryImplTest` (8) |
| 11–16 | اليوميات، يوم/حدث، التسجيل الكتابي، وسائط اليوم، ترتيب اليوميات | `ui/diary/` (`DiaryHomeScreen`, `DayScreen`, `EntryEditorScreen`) | `DiaryTimeTest` (14), `DiaryDatabaseTest` (9), `EntryEditorViewModelTest` (7), `DiaryNoteSyncTest` (8) |
| 17–19 | الأسبوع، الشهر، السنة | `WeekScreen`, `MonthScreen` + `MonthGrid`, `YearScreen` | `DiaryPeriodViewModel`, `TimelineBuilderTest` (8)، وعدّادات الجمع/الصيغ في `strings.xml` |
| 20 | «في مثل هذا اليوم» | `DayScreen.kt:110` عبر `DayViewModel.onThisDay` | `DiaryTime.onThisDay` + `TimelineBuilderTest` |
| 21 | الخط الزمني العام | `ui/timeline/TimelineScreen.kt` | `TimelineViewModelTest`, `TimelineBuilderTest` |
| 22 | البحث | `ui/search/`, `data/repository/SearchRepositoryImpl.kt` | `SearchRepositoryImplTest` (16), `SearchQueryParserTest` (19) |
| 23–24 | الأشخاص والأماكن | `ui/search/OrganizationScreens.kt` + `data/local/dao/ReferenceDaos.kt` | `RecordLinkingTest` (7), `ReferenceSyncTableTest`, `ReferenceSyncMigrationTest` |
| 25–26 | «قرب مني» والموقع عند الطلب فقط، بلا تتبّع خلفي | `ui/nearby/`, `LocationPickerViewModel` | `NearbyViewModelTest` (6) + `check-security.sh` يفشل عند أي `ACCESS_BACKGROUND_LOCATION` |
| 27 | قاعدة البيانات المحلية (Room) بترحيلاتها | `data/local/` — المخطط نسخة 4 | `ReferenceSyncMigrationTest`, `SyncMetaMigrationTest` (4+4)، وتوليد المخطط في CI |
| 28 | المزامنة: طابور، رفع، تنزيل، تعارضات، حذف لا يُبعث حيًّا | `data/sync/` (`SyncEngine`, `SyncTables`, `SyncWorker`) | `SyncEngineTest` (15), `LinkSyncTest` (8), `DeletedMemorySyncTest` (4), `ConflictResolverTest` (8) |
| 29–31 | Supabase للمصادقة/Postgres/التخزين، وRLS، والكتابة الخاصة بالمستخدم | `supabase/schema.sql` + `supabase/verify/` | `ci/check-schema.py` يطبّق المخطط **مرتين** على PostgreSQL حقيقي ويشغّل 24 تأكيدًا؛ `SupabaseAuthRepositoryTest` (11), `SupabaseContractTest` (16), `SchemaSecurityTest` (11) |
| 32–34 | الصور، الفيديو، الصوت كوسائط فقط بلا تحليل | `util/ImageOptimizer.kt`, `util/JpegMetadata.kt`, `ui/camera/` | `ImageOptimizerTest` (7), `ImageOptimizerTransformTest` (5), `JpegMetadataTest` (14), `CameraControlsTest` (13) |
| 35 | الحساب | `ui/profile/ProfileScreen.kt` | `ProfileViewModelTest` (9) |
| 36 | النسخ الاحتياطي: تصدير/استيراد إلى مجلد يختاره المستخدم | `data/repository/BackupRepositoryImpl.kt`, `util/backup/` | `BackupRepositoryImplTest` (13), **`BackupTwoDeviceTest` (5) — قاعدتا Room على أرشيف واحد**, `BackupRecordsTest` (10), `BackupPlannerTest` (7) |
| 37 | الحذف: من التطبيق، وبلا عودة ما حُذف | `AccountDeletionTest` (15) + دالّتا حذف `security definer` في المخطط + القبور | فحوص المخطط للتأكد من صلاحيات الدالّتين ومن `revoke` عن `anon` |
| 38–39 | الخصوصية والأمان (بلا تعقّب، بلا تسجيل حسّاس، نسخ احتياطي معطّل، R8) | `data_extraction_rules.xml`, `backup_rules.xml`, `MmLog`, `proguard-rules.pro` | `PrivacyDefaultsTest` (9), `ClientSecurityTest` (7), `check-security.sh` |
| 40–41 | التصميم (Material 3، لون أساسي) وتجربة الاستخدام | `ui/theme/`, 5 تبويبات، `QuickAddSheet` | `values/strings.xml` و`values-en` (548 نصًا و20 جمعًا) يفحصها `check-strings.py` |
| 42–46 | شاشة اليوم، اليوميات القديمة، التقويم، إحصاءات الحياة، خصوصية كل سجل | `ui/diary/`، `LifecycleRepositories.LifeStatsCalculator`، `Visibility` لكل سجل | `DateConstraintTest` (8), `SearchQueryParserTest`, وRLS للخصوصية على الخادم |
| 47 | الأداء | `PerformanceTest` (5) وقياس التجميع على مقياس حقيقي | حدود زمنية في الاختبارات، وتقرير CI يذكرها عند الفشل |
| 48 | الاختبارات | `app/src/test/java/com/memorymap/` — 52 صنفًا | **480 اختبارًا، 0 فشل** في آخر تشغيل |
| 49–50 | هيكل المشروع والمراحل العشر | `README.md` (الخارطة)، `docs/READINESS.md` | كل مرحلة أُثبتت بتشغيل CI قبل التالية |
| 51–53 | قاعدة «مرحلة واحدة، ثم بناء واختبار»، وألا يُقال «تمّ» بلا اختبار، والتقرير بالعربية | سجل الإيداعات وتقارير المراحل | كل مرحلة لها تشغيل أخضر باسمه؛ والأخطاء موثّقة لا مخفية |
| 54 | تجهيز النشر: README, LICENSE, CHANGELOG, سياسة خصوصية, تعليمات إصدار وتوقيع, أيقونة تكيّفية | `README.md`, `LICENSE`, `CHANGELOG.md`, `docs/legal/PRIVACY_{AR,EN}.md`, `docs/RELEASE.md`, `mipmap-anydpi/ic_launcher.xml` | `check-store-metadata.py` + وجود الملفات؛ **والتطبيق غير منشور، وهذا مذكور في كل مستند** |
| 55 | توثيق حدود الخدمات الخارجية | `docs/SERVICE_LIMITS.md` | أرقام متحقَّق منها من صفحات المزوّدين بتاريخ 2026-09-27 وروابطها |
| 56 | تجربة استخدام بسيطة (لا يعرف المستخدم Room ولا Supabase) | تبويبات وشاشات فقط، بلا كلمات تقنية في الواجهة | `check-strings.py` + مراجعة النصوص |
| 57–58 | البدء من Phase 1 بعد نجاح البناء، والعربية افتراضيًا والإنجليزية اختياريًا، والكود والتعليقات بالإنجليزية | تاريخ الإيداعات، `values/` و`values-en/`، الكود | أول إيداع بنية كاملة؛ `Locale.kt`؛ وكل تعليق في الكود بالإنجليزية |

## ما لا يُثبته هذا المستند

- **لم يُطبَّق المخطط على مشروع Supabase مستضاف** بعد؛ أثبته هو التطبيق على PostgreSQL
  حقيقي في CI (24 تأكيدًا)، وهذا أقوى ما يمكن لمشغّل بلا مشروع أن يفعله.
- **لم يُشغَّل التطبيق على جهاز**. كل ما في الجدول أعلاه إما اختبار JVM/Robolectric أو
  فحص ثابت؛ ما يخصّ الكاميرا وGPS والمشروع الحقيقي مذكور في `docs/MANUAL_QA.md`
  ولم يُنفَّذ.
- **لم تُرفع أي نسخة** إلى أي متجر ولا إلى GitHub Releases.
- **النسخ الحديثة من المكتبات** غير قابلة للاستخدام مع `compileSdk 36`؛ السبب بأدلّته في
  `docs/READINESS.md` (المرحلة 5).
