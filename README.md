# MyAI — مساعد ذكي عربي لأندرويد

تطبيق أندرويد حقيقي (Kotlin + Android Views) يعمل كمساعد شخصي بالعربية: واجهة محادثة
من اليمين إلى اليسار، وزر عائم فوق التطبيقات الأخرى، ومحرّك أوامر يحوّل الطلب العربي إلى
خطوات منفّذة عبر واجهات أندرويد الرسمية — مع تحقق من نجاح كل خطوة، وتأكيد صريح قبل أي
خطوة حسّاسة.

الجهاز المستهدف: **Honor 400 – MagicOS** (يعمل أيضًا على أي جهاز Android 8.0+).

| | |
|---|---|
| الحزمة | `com.myai.assistant` — نسخة التصحيح `com.myai.assistant.debug` |
| الإصدار | `1.0.0` (versionCode 1) |
| أدنى إصدار | Android 8.0 — API 26 |
| هدف البناء | Android 15 — API 35 |
| اللغات | Kotlin 2.0.21، Gradle 8.11.1، AGP 8.7.3، Views/Material (بدون Compose) |
| الاختبارات | 46 اختبار وحدة — كلها ناجحة في CI |
| حجم APK | ‎6.5 MB (تصحيح) |

لا يوجد مفتاح API واحد مكتوب داخل الكود أو المستودع. أي مفتاح يُدخله المستخدم يُشفَّر
بمفتاح AES-GCM داخل Android Keystore (`core/SecureStore.kt`).

---

## 1. التنزيل والتثبيت

**رابط التنزيل المباشر (APK موقَّع وجاهز للتثبيت):**

> https://github.com/slam27893-del/Mla/releases/latest/download/MyAI-1.0.0-debug.apk

المستودع خاص (private)، لذلك يجب أن تكون مسجّلًا في GitHub بحساب يملك صلاحية الوصول
(`slam27893-del`) ثم تفتح الرابط، أو تنزّل الملف من صفحة الإصدارات:
<https://github.com/slam27893-del/Mla/releases>

| | |
|---|---|
| الاسم | `MyAI-1.0.0-debug.apk` |
| الحجم | 6,478,291 بايت (≈ 6.2 MiB) |
| SHA-256 | `6af530db7d1e0c1a73b9c40c4e2f64f0824539ecd78e50d6548c361dfae1acf4` |
| التوقيع | APK Signature Scheme v2 — «Verifies» (مفتاح تصحيح) |

للتحقق من سلامة الملف بعد التنزيل على الحاسوب:

```bash
sha256sum MyAI-1.0.0-debug.apk
```

خطوات التثبيت على Honor 400 وشرح الأذونات خطوة بخطوة:
**[docs/INSTALL-HONOR400.md](docs/INSTALL-HONOR400.md)**

---

## 2. بنية المشروع

```
Mla/
├── settings.gradle.kts · build.gradle.kts · gradle.properties
├── gradle/libs.versions.toml              ← كل الإصدارات في مكان واحد
├── gradlew · gradle/wrapper/              ← Gradle 8.11.1
├── .github/workflows/android.yml          ← بناء + اختبار + فحص APK + نشر
├── docs/
│   ├── INSTALL-HONOR400.md                ← دليل التثبيت على الجهاز
│   └── ARCHITECTURE.md                    ← شرح أعمق لتدفق الأوامر
└── app/
    ├── build.gradle.kts · proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/myai/assistant/
        │   │   ├── MyAiApplication.kt          ← الحقن اليدوي (AppContainer)
        │   │   ├── core/                       ← النماذج + SecureStore
        │   │   │   ├── model/Models.kt
        │   │   │   └── SecureStore.kt
        │   │   ├── data/                       ← الإعدادات + سجل المحادثة
        │   │   ├── ai/                         ← طبقة مزوّدي الذكاء الاصطناعي
        │   │   ├── agent/                      ← المخطِّط + المنفّذ + التحقق
        │   │   ├── tools/                      ← 20 أداة تنفيذ حقيقية
        │   │   ├── accessibility/              ← خدمة الوصولية (اختيارية)
        │   │   ├── service/                    ← الفقاعة العائمة + لوحة المحادثة
        │   │   ├── ui/                         ← الأنشطة + محوّل المحادثة
        │   │   └── util/                       ← معالجة النص العربي
        │   └── res/                            ← 14 واجهة RTL + 145 نص عربي
        └── test/java/com/myai/assistant/       ← 5 ملفات اختبار (46 اختبارًا)
```

### أين يوجد كل شيء فعليًا

| الطبقة | الملف | المسؤولية |
|---|---|---|
| نقطة الدخول | `MyAiApplication.kt:32` | `AppContainer` يبني المتجرات والمزوّد والمخطِّط مرة واحدة |
| إعدادات | `data/SettingsRepository.kt:26` | `StateFlow<AppSettings>` محفوظ في SharedPreferences |
| السجل | `data/ConversationStore.kt:21` | JSON محلي في مجلد خاص، ويمكن تعطيل الحفظ كليًا |
| سر المفتاح | `core/SecureStore.kt:26` | AES-256-GCM + Keystore، بدون أي كتابة في Logcat |
| مزوّد AI | `ai/OpenAiCompatibleProvider.kt:24` | أي خادم متوافق مع OpenAI Chat Completions |
| مصنع المزوّد | `ai/ProviderFactory.kt:10` | نقطة التسجيل الوحيدة لموفّر جديد |
| المخطِّط | `agent/RuleBasedPlanner.kt:18` و `agent/LlmPlanner.kt:14` | قواعد عربية محلية، أو نموذج لغوي عبر الشبكة |
| المنفّذ | `agent/AgentEngine.kt:44` | ينفّذ الخطوات بالترتيب ويوقف السلسلة عند الفشل |
| الأداة | `agent/Tool.kt:44` | عقد الأداة: معاملات + مخاطرة + تحقق |
| السجل الأدوات | `tools/Tools.kt` | `defaultTools()` تعيد 20 أداة |
| بوابة المحادثة | `agent/ChatController.kt:27` | دورة واحدة فقط في أي لحظة (single-flight) |
| الفقاعة | `service/FloatingAssistantService.kt:43` | خدمة أمامية + نافذة `TYPE_APPLICATION_OVERLAY` |
| الوصولية | `accessibility/MyAiAccessibilityService.kt:27` | تنفّذ `ScreenController` لقراءة الشاشة واللمس |

---

## 3. كيف يعمل الأمر

```
"افتح واتساب وأرسل لأحمد..."  (نص عربي)
        │
        ▼
 ChatController  ──► Planner (قواعد محلية ← أو LLM إن كان مفعّلًا)
        │                └── يعيد خطوات: [{tool, args}, ...]
        ▼
 AgentEngine
   ├─ لكل خطوة: أداة معروفة؟ معاملات صحيحة؟ وصولية متاحة؟
   ├─ خطوة SENSITIVE؟ ──► حوار تأكيد للمستخدم (داخل التطبيق أو فوق الشاشة)
   ├─ Tool.run() ──► Intent رسمي / إجراء وصولية
   └─ ToolOutcome ──► Verification = VERIFIED | DISPATCHED | UNVERIFIED | FAILED
        │
        ▼
 ChatAdapter يعرض كل خطوة بلون وحالة، ولا يعرض "تم" إلا لما تحقق فعليًا
```

### نموذج التحقق (مهم)

| الحالة | المعنى |
|---|---|
| `VERIFIED` | تحققنا من الأثر فعليًا (مثل: قراءة حالة الفلاش، وجود التطبيق) |
| `DISPATCHED` | أُرسل الـIntent للنظام، ولا نملك دليلًا على النتيجة (شاشة الطلب تُفتح فقط) |
| `UNVERIFIED` | تم الإرسال لكن لا توجد طريقة للتحقق (مشاركة، حافظة) |
| `FAILED` | فشل واضح: لا تطبيق، استثناء، أو رفض المستخدم |

لهذا لا يقول التطبيق أبدًا "تم إرسال الرسالة" — بل "فُتح محرّر الرسائل، أرسلها بنفسك".

---

## 4. الأدوات العشرون

| المجموعة | الأدوات |
|---|---|
| التطبيقات والويب | `open_app` · `open_url` · `web_search` |
| الاتصال | `dial_number` (يفتح لوحة الاتصال فقط) · `compose_sms` (يفتح محرّر الرسالة فقط) · `share_text` · `copy_to_clipboard` |
| الوقت | `set_alarm` · `set_timer` · `create_calendar_event` |
| الجهاز | `device_status` · `flashlight` · `media_control` · `open_settings` |
| التحكم بالشاشة (وصولية) | `screen_summary` · `click_text` · `set_text` · `scroll_screen` · `global_action` |
| مساعدة | `wait` |

`open_settings.panel` تقبل: `settings`, `wifi`, `bluetooth`, `sound`, `display`,
`battery`, `location`, `accessibility`, `apps`, `notifications`, `data`,
`default_apps`, `overlay`, `app_details`.

---

## 5. ما لا يستطيعه التطبيق (بصراحة)

- **لا يفعّل/يعطّل Wi-Fi أو بيانات الجوال أو وضع الطيران برمجيًا.** أندرويد منذ API 29
  يمنع ذلك لتطبيقات الطرف الثالث؛ لذلك تفتح `open_settings` لوحة الإعدادات ويُترك
  التبديل للمستخدم.
- **لا يرسل رسالة SMS أو مكالمة تلقائيًا.** يُستخدم `ACTION_DIAL` و`ACTION_SENDTO` فقط،
  فيبقى القرار النهائي للمستخدم.
- **لا يتجاوز شاشة القفل، ولا يقرأ كلمات المرور، ولا يعمل في تطبيقات تحظر لقطات الشاشة.**
- **لا يتحكم بإعدادات نظام محمية** (صلاحية الجهاز، VPN، إلغاء تثبيت تطبيق نظام…).

---

## 6. الخصوصية والأمان

- **بدون مفاتيح في المستودع:** لا توجد قيمة سرية واحدة في الكود أو في تاريخ Git.
  المفتاح يُدخل من الإعدادات ويُخزَّن مشفَّرًا.
- **الحفظ اختياري:** إيقاف "حفظ المحادثة" يمنع أي كتابة على القرص، ويُمسح السجل الحالي.
- **الشبكة:** لا يخرج أي طلب إلا إذا اخترت مزوّدًا سحابيًا. المخطِّط الافتراضي يعمل
  محليًا بالكامل. يوجد `network_security_config.xml` يمنع الاتصال غير المشفّر.
- **الأذونات:** لا شيء يُطلب تلقائيًا. كل إذن يُشرح في «مركز الأذونات» مع سببه،
  والوصولية والتراكب **اختياريان** ويمكن إيقافهما في أي وقت.
- **الشفافية:** شاشة «الخصوصية» تشرح ما يُرسل ومتى، وحوار التأكيد يظهر قبل كل خطوة حسّاسة.
- **البطارية:** لا مهام دورية ولا `WakeLock`. الخدمة الأمامية تعمل فقط عند تفعيل الفقاعة،
  وتُوقف نفسها إذا فُقد إذن التراكب.

---

## 7. ربط مزوّد ذكاء اصطناعي (اختياري)

التطبيق يعمل بدون إنترنت باستخدام المخطِّط القائم على القواعد العربية. لتفعيل الفهم
الحر للنصوص:

1. الإعدادات ← قسم **«محرك الذكاء الاصطناعي»**.
2. اختر **«محرك AI سحابي»** (الخيار الثالث «نموذج محلي على الجهاز» معطّل بانتظار محرك).
3. أدخل:
   - **عنوان الخادم** — مثل `https://api.openai.com/v1` أو عنوان خادمك المحلي.
   - **اسم النموذج** — مثل `gpt-4o-mini` أو أي اسم يدعمه خادمك.
   - **مفتاح API (اختياري)** — يُخزَّن مشفَّرًا، ولا يظهر مجددًا بعد الحفظ؛ ولمسحه
  استخدم «مسح المفتاح المحفوظ».
4. اضغط **«اختبار الاتصال»** للتأكد قبل الاستخدام. (الخيار «محرك أوامر محلي» يعيدك
   للعمل دون إنترنت.)

يُرسل الطلب إلى `POST {baseUrl}/chat/completions` بصيغة JSON قياسية، ويُطلب من النموذج
إعادة خطة بالصيغة:

```json
{"reply":"...","steps":[{"tool":"open_app","args":{"app":"واتساب"},"title":"فتح واتساب"}]}
```

أي أداة غير معروفة أو معامل ناقص يوقف التنفيذ قبل أن يبدأ (`agent/PlanParser.kt`).
إن فشل النموذج، يعود التطبيق تلقائيًا إلى المخطِّط المحلي (`agent/FallbackPlanner.kt`).

---

## 8. البناء من المصدر

المتطلبات: JDK 17، Android SDK مع platform 35، اتصال بالشبكة أول مرة للتنزيل.

```bash
git clone https://github.com/slam27893-del/Mla.git
cd Mla

./gradlew testDebugUnitTest      # 46 اختبارًا
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug              # 0 أخطاء
```

أو افتح المجلد في Android Studio (Ladybug أو أحدث) ثم Run.

آخر بناء على GitHub Actions: كل الخطوات ناجحة — الاختبارات، APK للتصحيح، APK للإصدار
مع R8، فحص Lint، والتحقق من توقيع الـAPK بـ`apksigner`.

---

## 9. التوسعة

**إضافة أداة:**
1. أنشئ `object`/`class` ينفّذ `Tool` (`agent/Tool.kt:44`) وحدّد `id`, `parameters`,
   `risk`, `requiresAccessibility`, و`run()` يعيد `ToolOutcome` مع حالة التحقق.
2. أضفه إلى `defaultTools()` في `tools/Tools.kt`.

**إضافة مزوّد AI:** نفّذ `AiProvider` (`ai/AiProvider.kt:21`)، ثم سجّله في
`ai/ProviderFactory.kt:10` ونوعه في `EngineType`.

**نموذج محلي على الجهاز:** نفّذ `LocalInferenceEngine` (`ai/AiProvider.kt:43`) وضع
المحرك في `LocalModelProvider`. الواجهة موجودة، والمحرك الافتراضي
`UnavailableLocalEngine` يفشل برسالة واضحة بدلًا من الادعاء الكاذب.

---

## 10. الحالة

- ✅ يبني ويعمل: آخر تشغيل CI — 46 اختبارًا (0 فشل، 0 خطأ، 0 متخطى)، Lint 0 أخطاء
  (32 تحذيرًا غير حاجز)، APK موقَّع بـv2 وتحقّق منه `apksigner` داخل CI.
- ⚠️ بعض التحذيرات غير الحاجزة: إصدارات مكتبات أحدث متاحة، و`commit()` مقصود في
  `SecureStore` لأنه يجب معرفة إن كان الحفظ قد نجح.
- 🧪 **لم يُجرَّب بعد على جهاز Honor 400 فعلي.** كل ما ورد أعلاه من نتائج البناء
  والاختبار الآلي حقيقي ومأخوذ من CI، أما سلوك الأذونات على MagicOS فيحتاج تأكيدًا
  منك بعد التثبيت.

---

## English summary

**MyAI** is a Kotlin Android assistant with an Arabic RTL chat UI and a floating
bubble over other apps. Commands are planned (local rule engine or any
OpenAI-compatible endpoint) and executed step by step through official Android
APIs, with per-step verification (`VERIFIED / DISPATCHED / UNVERIFIED / FAILED`)
and explicit confirmation for sensitive actions. An optional Accessibility
service enables screen reading and tapping. No API keys are stored in the repo;
user keys are encrypted with AES-GCM in the Android Keystore. The app never
claims to change settings Android forbids (`Wi-Fi`, mobile data, airplane mode
are opened as settings panels instead).

Build: `./gradlew testDebugUnitTest assembleDebug lintDebug` (JDK 17, SDK 35).
Install guide: [docs/INSTALL-HONOR400.md](docs/INSTALL-HONOR400.md).
