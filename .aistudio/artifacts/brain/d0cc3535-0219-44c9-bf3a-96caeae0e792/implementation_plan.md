# Адаптация и запуск полностью автономного «Голосового ИИ» (Infinix Note 30, Helio G99, llama.cpp b3925)

Инженерный план по доведению проекта до 100% автономного рабочего состояния на смартфоне Infinix Note 30 (Android, процессор MediaTek Helio G99 / arm64-v8a, 8 ГБ RAM) с локальной моделью Qwen2.5 через нативный движок llama.cpp (тег b3925) без интернета, без облачных API и без VPN.

---

## Пользовательские решения и критические развилки

> [!IMPORTANT]
> В плане зафиксированы три обязательных требования:
> 1. **Git Submodule для llama.cpp**: `llama.cpp` оформляется как официальный git submodule на теге `b3925` (`0e41b300ed28f7fe185d938b2e3d56a0bf7411ed`). В проект добавляются файл `.gitmodules`, точные консольные команды инициализации и шаг `submodules: recursive` в GitHub Actions.
> 2. **Размещение и работа модели Vosk**: Модель Vosk для русского языка (`vosk-model-small-ru-0.22`, ~45 МБ) разворачивается во внутреннюю память приложения `context.filesDir/vosk/model/`. Реализуется двойной механизм: авто-распаковка из `assets/models/` через `StorageService.unpack` (если вшита) и возможность выбора/распаковки архива модели из памяти телефона (Downloads) без интернета.
> 3. **Аудит совместимости C API llama-jni.cpp с b3925**: Построчная верификация вызовов `llama_batch_get_one`, `llama_load_model_from_file`, цепочки сэмплеров `llama_sampler`, `llama_token_to_piece`, `llama_chat_apply_template` и флагов компилятора ARM64 SIMD.

---

## Реестр найденных ошибок и несоответствий

| # | Файл | Строка(и) | Суть проблемы и влияние на систему |
|---|------|-----------|-------------------------------------|
| 1 | Корень репозитория | — | Отсутствует файл `.gitmodules` и директория `app/src/main/cpp/llama.cpp`. Локальная сборка падает с `FATAL_ERROR` в CMake. |
| 2 | `app/build.gradle.kts` | 13–21 | Отсутствует декларативный блок `externalNativeBuild` и `ndk { abiFilters.add("arm64-v8a") }`. Локально в Android Studio `libllama-android.so` не компилируется. |
| 3 | `.github/workflows/release.yml` | 52–84 | Инъекция `externalNativeBuild` через скрипт на Python с заменой строк. Ломает предсказуемость сборки и декларативность Gradle. |
| 4 | `app/build.gradle.kts` | 6–7, 72–82 | Неиспользуемые облачные зависимости и плагины: `firebase.ai`, `firebase.appcheck.*`, `google.services`, `secrets-gradle-plugin`. Требуют `google-services.json` и `.env.example`. |
| 5 | `app/build.gradle.kts` | 24–35 | Блок `signingConfigs.release` жестко требует `my-upload-key.jks`, вызывая ошибку Gradle без переменных окружения. |
| 6 | `app/src/main/data/local/SettingsRepository.kt` | 17, 39 | Неверный репозиторий по умолчанию: `"vovik06302/local-voice-ai"` вместо `"vovik06302-ai/local-voice-ai"`. Проверка обновлений выдаёт ошибку 404. |
| 7 | `app/src/main/ui/components/SettingsDialog.kt` | 596 | Неверный плейсхолдер репозитория: `vovik06302/local-voice-ai`. |
| 8 | `app/src/main/voice/VoiceRecognitionManager.kt` | 88–180 | Использование только стандартного `SpeechRecognizer`, который на смартфонах без сервисов Google или без интернета возвращает `ERROR_NETWORK`. |
| 9 | `app/src/main/AndroidManifest.xml` | 1–45 | Отсутствует блок `<queries>` с намерениями `RecognitionService` и `TTS_SERVICE`, из-за чего на Android 11+ блокируется поиск речевых движков. |
| 10 | `/voiceai-source-project.zip` | корень | Избыточный zip-архив (~37 МБ), замедляющий сборку и захламляющий git-историю. |
| 11 | `app/src/main/domain/LocalModelManager.kt` | 80–203 | Нет импорта файлов `.gguf` из памяти телефона (Downloads / SD-карта) при недоступности Hugging Face без VPN. |

---

## 1. Нативная сборка: Git Submodule, CMake и аудит API b3925

### 1.1. Подключение Git Submodule на теге b3925
В корень репозитория добавляется конфигурация `.gitmodules`:
```ini
[submodule "app/src/main/cpp/llama.cpp"]
    path = app/src/main/cpp/llama.cpp
    url = https://github.com/ggerganov/llama.cpp.git
    branch = b3925
```
**Точные консольные команды для локального репозитория:**
```bash
# Добавление подмодуля с фиксацией тега b3925
git submodule add -b b3925 https://github.com/ggerganov/llama.cpp.git app/src/main/cpp/llama.cpp
cd app/src/main/cpp/llama.cpp
git checkout b3925
cd ../../../..
git commit -am "Подключен git submodule llama.cpp на теге b3925"
```
В `.github/workflows/release.yml`:
```yaml
- name: Checkout Code with Submodules
  uses: actions/checkout@v4
  with:
    submodules: recursive
```

### 1.2. Проверка совместимости `llama-jni.cpp` с C API тега b3925
Проведён детальный аудит всех вызовов API llama.cpp в `llama-jni.cpp`:
1. **`llama_batch_get_one`**:
   - Сигнатура в b3925: `llama_batch llama_batch_get_one(llama_token * tokens, int32_t n_tokens, llama_pos pos_0, llama_seq_id seq_id);`
   - В `llama-jni.cpp` (строки 363, 415):
     `llama_batch_get_one(&prompt_tokens[i], cur_batch, i, 0);`
     `llama_batch_get_one(&new_token_id, 1, n_cur, 0);`
     *Статус: Полностью совместимо (4 аргумента).*
2. **`llama_load_model_from_file` и параметры**:
   - В `llama-jni.cpp` (строки 134–138):
     `llama_model_params mparams = llama_model_default_params();`
     `mparams.n_gpu_layers = 0;`
     `llama_model *model = llama_load_model_from_file(path.c_str(), mparams);`
     *Статус: Полностью совместимо.*
3. **Новый Sampler API (появился в b39xx)**:
   - В `llama-jni.cpp` (строки 331–337, 384–385, 439):
     Используется `llama_sampler_chain_default_params()`, `llama_sampler_chain_init()`, `llama_sampler_chain_add()`, `llama_sampler_init_top_k()`, `llama_sampler_init_top_p()`, `llama_sampler_init_temp()`, `llama_sampler_init_dist()`, `llama_sampler_sample()`, `llama_sampler_accept()`, `llama_sampler_free()`.
     *Статус: Полностью совместимо именно с релизом b3925.*
4. **`llama_token_to_piece`**:
   - Сигнатура в b3925: `int32_t llama_token_to_piece(const struct llama_model * model, llama_token token, char * buf, int32_t length, int32_t lstrip, bool special);`
   - В `llama-jni.cpp` (строка 342):
     `llama_token_to_piece(session->model, tok, piece_buf.data(), piece_buf.size(), 0, false);`
     *Статус: 6 параметров, полное совпадение.*
5. **Флаги компиляции ARM64-v8a в CMakeLists.txt**:
   - `add_compile_options(-O3 -DNDEBUG -flto -march=armv8-a+fp+simd)`
   - Оптимизировано для Cortex-A76/A55 на Helio G99 с NEON и FP16 SIMD.
   - Linkage: `llama`, `ggml`, `log`, `android` с `-DANDROID_STL=c++_shared`.

### 1.3. Интеграция в `app/build.gradle.kts`
Внедрение блока без необходимости сторонних python-скриптов:
```kotlin
val llamaCppDir = file("src/main/cpp/llama.cpp")
val hasLlamaSource = llamaCppDir.exists() && file("src/main/cpp/llama.cpp/CMakeLists.txt").exists()

android {
  defaultConfig {
    ndk {
      abiFilters.add("arm64-v8a")
    }
    if (hasLlamaSource) {
      externalNativeBuild {
        cmake {
          cppFlags("-O3 -DNDEBUG -flto -march=armv8-a+fp+simd")
          arguments(
            "-DANDROID_STL=c++_shared",
            "-DLLAMA_STANDALONE=OFF",
            "-DLLAMA_BUILD_COMMON=OFF",
            "-DLLAMA_BUILD_TESTS=OFF",
            "-DLLAMA_BUILD_EXAMPLES=OFF",
            "-DLLAMA_BUILD_SERVER=OFF"
          )
        }
      }
    }
  }

  if (hasLlamaSource) {
    externalNativeBuild {
      cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
      }
    }
  }
}
```
*Удаляется шаг «Enable externalNativeBuild in build.gradle.kts» с python3 из `.github/workflows/release.yml`.*

---

## 2. Автономная работа: Офлайн STT (Vosk), TTS и импорт GGUF

### 2.1. Офлайн-распознавание Vosk: размещение и запуск
1. **Зависимость**: Добавляется `implementation("com.alphacephei:vosk-android:0.3.47")`.
2. **Размещение модели на устройстве**:
   - Модель: `vosk-model-small-ru-0.22` (размер ~45 МБ, распакованная занимает ~70 МБ).
   - Путь в файловой системе телефона: `context.filesDir/vosk/model/` (приватное защищённое хранилище приложения).
   - Структура файлов модели:
     ```
     context.filesDir/vosk/model/
     ├── am/
     │   └── final.mdl
     ├── conf/
     │   ├── mfcc.conf
     │   └── model.conf
     ├── graph/
     │   ├── HCLG.fst
     │   └── words.txt
     └── ivector/
         └── final.mat
     ```
3. **Механизм загрузки и инициализации**:
   - **Автоматический способ**: Если zip-архив или папка модели вложена в `app/src/main/assets/models/vosk-model-small-ru-0.22`, `StorageService.unpack(context, "models/vosk-model-small-ru-0.22", "vosk/model", ...)` распаковывает её при первом запуске в `context.filesDir`.
   - **Ручной офлайн-способ (без раздувания APK)**: Пользователь скачивает архив на ПК/телефон и через кнопку в настройках «Выбрать модель Vosk» указывает `.zip` или папку через Storage Access Framework. Приложение распаковывает архив напрямую в `context.filesDir/vosk/model/`.
   - **Инициализация**:
     ```kotlin
     val model = Model(modelPath.absolutePath)
     val recognizer = Recognizer(model, 16000.0f)
     val speechService = SpeechService(recognizer, 16000.0f)
     speechService.startListening(listener)
     ```
4. **Гибридный откат**: Если модель Vosk ещё не установлена, `VoiceRecognitionManager` использует системный `SpeechRecognizer` с `EXTRA_PREFER_OFFLINE` и выводит пользователю подсказку о статусе.

### 2.2. Синтез речи (TTS)
1. Проверка наличия голоса `ru-RU` с проверкой `voice.isNetworkConnectionRequired == false` и отсутствия флага `KEY_FEATURE_NOT_INSTALLED`.
2. При отсутствии офлайн-голоса отображается баннер и кнопка быстрого перехода в настройки системы: `Intent("com.android.settings.TTS_SETTINGS")`.
3. Исправление предупреждений устаревших конструкторов `Locale` в `TextToSpeechManager`.

### 2.3. SAF-импорт файлов .gguf из памяти телефона
1. Пользователь может скопировать модель с ПК через USB-кабель в папку `Download` на телефоне.
2. В `LocalModelManager` реализуется `importGgufFromUri(uri: Uri, targetDescriptor: ModelDescriptor, onProgress: (Float) -> Unit)`:
   - Открытие `InputStream` через `ContentResolver`.
   - Запись в `context.filesDir/models/<fileName>.part` буферами по 64 КБ (минимальное потребление памяти).
   - Проверка первых 4 байт на соответствие сигнатуре `GGUF` (`0x47 0x47 0x55 0x46`).
   - Расчёт SHA-256 на лету и сверка с эталонной контрольной суммой из `ModelCatalog`.
   - Атомарное переименование в целевой файл при успешной проверке.
3. В интерфейсе (`HomeScreen` и `SettingsDialog`) добавляется кнопка с лаунчером `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())` для типа `*/*` или `.gguf`.

---

## 3. Очистка и удаление лишних компонентов

1. **Удаление плагинов из `app/build.gradle.kts`**:
   - `alias(libs.plugins.secrets)`
   - `alias(libs.plugins.google.services)`
   - Блок `secrets { ... }`
   - Блок `googleServices { ... }`
2. **Удаление зависимостей**:
   - `platform(libs.firebase.bom)`
   - `libs.firebase.ai`
   - `libs.firebase.appcheck.recaptcha`
   - `libs.firebase.appcheck.debug`
3. **Удаление лишних файлов**:
   - Удаление `/voiceai-source-project.zip` (~37 МБ).
   - `.env.example` и `.env` больше не требуются сборщику (так как удалён secrets-plugin).
   - Сохранение `metadata.json` (критично для платформы AI Studio).

---

## 4. Сборка, подпись и обновление

1. **Подпись**:
   В `app/build.gradle.kts`:
   ```kotlin
   signingConfigs {
     create("release") {
       val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
       val keyFile = file(keystorePath)
       if (keyFile.exists()) {
         storeFile = keyFile
         storePassword = System.getenv("STORE_PASSWORD") ?: "android"
         keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
         keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
       }
     }
     create("debugConfig") {
       storeFile = file("${rootDir}/debug.keystore")
       storePassword = "android"
       keyAlias = "androiddebugkey"
       keyPassword = "android"
     }
   }
   ```
2. **GitHub Updates**:
   - В `SettingsRepository.kt` заменить `"vovik06302/local-voice-ai"` на `"vovik06302-ai/local-voice-ai"`.
   - В `SettingsDialog.kt` исправить плейсхолдер.
   - В `AndroidManifest.xml` добавить `<queries>` для системных речевых служб.

---

## 5. Документация (README.md) и Чек-лист проверки

Создание полноценного файла `README.md` на русском языке, содержащего:
1. Описание функционала и поддерживаемых моделей (Qwen2.5 1.5B Q4_K_M и 0.5B Q4_K_M).
2. Пошаговую инструкцию сборки APK через GitHub Actions и Android Studio.
3. Инструкцию по загрузке моделей (прямо с телефона или через USB-кабель с ПК) и модели Vosk.
4. Настройку подключения к локальному ПК (Ollama / llama-server).
5. Чек-лист проверки на смартфоне Infinix Note 30.
