# log-masking-spring-boot-starter

[English](README.md) · Русский

[![CI](https://github.com/laipan009/log-masking-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/laipan009/log-masking-spring-boot-starter/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)

**Маскирует именованные персональные данные и секреты в логах Spring Boot / Logback.** Правила
задаются через YAML, `@Mask`, собственные политики или явный API `LogMasker`.

```java
log.info("email=user@example.com, status=OK");
// email=***, status=OK
```

> **Экспериментальная версия: готовится первая публичная alpha.** Автор не проверял стартер в
> production. API и покрытие по умолчанию могут меняться. Маскирование не гарантирует, что в логах
> нет чувствительных данных: проверяйте собственные сценарии логирования и прочитайте ограничения ниже.

## Установка

Нужен JDK 21. Maven Wrapper в репозитории сам скачает Maven 3.9; при локальной установке подойдёт
Maven 3.6.3+. Сборка использует Spring Boot 3.5.16; совместимость с другими версиями отдельно не
проверялась.

Артефакта пока нет в Maven Central. Соберите и установите его локально:

```sh
./mvnw clean verify
./mvnw install -DskipTests
```

```xml
<dependency>
    <groupId>io.github.laipan009.logmasking</groupId>
    <artifactId>log-masking-spring-boot-starter</artifactId>
    <version>0.3.0-SNAPSHOT</version>
</dependency>
```

Defaults покрывают конкретные имена вроде `fullName`, `email`, `phone`, `passportNumber`, `password` и
`accessToken`, а также секретные заголовки. Общие имена вроде `comment`, `amount`, идентификаторов и
полей конкретного бизнеса нужно настраивать явно.

## Как это работает

Стартер оборачивает encoder'ы поддерживаемых appender'ов Logback и маскирует уже отрендеренную строку
до того, как байты будут записаны. Это покрывает сообщения библиотек, stack trace и MDC. Обёртка
работает и с текстовыми, и со структурными encoder'ами; см. ограничение для структурного вывода ниже.

```text
DefaultMaskingPolicy ----+
PropertiesMaskingPolicy -+
MaskFieldIndex (@Mask) --+--> MaskingPolicyRegistry --> LineMasker / LogMasker
Custom MaskingPolicy ---+

Application / libraries --> SLF4J --> Logback --> supported appender
    --> MaskingEncoder (delegate rendering + LineMasker) --> console / file
```

Подробности — порядок старта, устройство сканера, явный путь и проектные решения — в
[архитектуре](docs/architecture.ru.md).

Политики объединяют имена из четырёх источников:

| Источник | Где | Назначение |
|---|---|---|
| Defaults | `DefaultMaskingPolicy` | Общие чувствительные имена и секретные заголовки; отключаются через `use-default-fields: false` |
| Аннотации | `@Mask` из `io.github.laipan009.logmasking.core` | Индексируют имена аннотированных полей и их стратегии |
| Конфигурация | `logging.masking.*` | Добавляет имена и стратегии, в том числе для полей во внешних DTO |
| Бины приложения | `MaskingPolicy` | Вычисляемые имена, контекстные правила или правила из companion-библиотеки |

Для фиксированных списков используйте конфигурацию. Собственный бин политики полезен, когда имена
вычисляются, когда маскирование JSON зависит от соседних полей или когда правила общие для нескольких
приложений.

## Совпадение и замена

Совпадение точное и без учёта регистра. Полный список defaults — в `DefaultMaskingPolicy`. Сканер
распознаёт такие формы:

| Форма | Вход | Выход |
|---|---|---|
| Обычная пара или query | `fullName=John Smith, attempt=3`, `?snils=1234&page=0` | `fullName=***, attempt=3`, `?snils=***&page=0` |
| JSON | `{"passportNumber":300000,"token":true}` | `{"passportNumber":"***","token":"***"}` |
| Вложенное значение или значение в кавычках | `address={firstName=John}`, `Snils='123'` | `address=***`, `Snils='***'` |
| Секретный заголовок | `Headers: [Authorization=Bearer example]` | `Headers: [Authorization=***]` |
| Заголовок через двоеточие | `Authorization: Bearer example` | `Authorization: ***` |

Значения замены — строки, поэтому JSON-числа и boolean при полном маскировании становятся `"***"`.
Границы в обычном тексте — запятые, закрывающие скобки, амперсанды и переводы строк. Пробелы входят в
значение: `fullName=John Smith` маскируется целиком, а `fullName=Smith, John` превращается в
`fullName=***, John`. У значений JSON в кавычках и вложенных значений своя обработка границ.

Голый синтаксис `Name: value` распознаётся **только для секретных заголовков**. Обычные метки часто
открывают свободный текст без надёжного конца; их маскирование могло бы съесть остаток сообщения.

### Безымянные значения и null

Encoder не может опознать безымянное значение. Если логируете getter, маскируйте явно:

```java
log.info("request from {}", dto.getFullName()); // Безымянное значение останется видимым.
log.info("request from {}", logMasker.field(dto, Dto::getFullName));
// request from fullName=***

log.info("request {}", dto); // Именованные поля из toString следуют настроенной политике.
```

`null` не меняется, например `snils=null`.

## Стратегии

| Стратегия | Пример | Назначение |
|---|---|---|
| `FULL` (по умолчанию) | `***` | Скрыть значение целиком |
| `LAST4` | `***8900` | Оставить четыре последних кодовых точки Unicode; значения из четырёх и меньше символов маскируются целиком |
| `HASH` | `#a1b2c3d4` | Первые восемь hex-символов SHA-256 для приблизительной корреляции |

`field-strategies` тоже добавляет имена в маскируемое множество; не дублируйте их в
`additional-masked-fields`. Явные стратегии могут перекрыть неявное полное маскирование имени из
defaults. Конфликты между явными стратегиями разрешаются как `FULL > HASH > LAST4`, независимо от
порядка бинов. Секретные заголовки всегда маскируются полностью.

`HASH` — не криптографическая защита: предсказуемые значения можно подобрать перебором, а короткий хеш
может давать коллизии. `LAST4` намеренно раскрывает часть исходного значения.

Значение, которое уже имеет форму `HASH` (`#` и восемь hex-символов в нижнем регистре), повторно не
хешируется, поэтому тела из `maskJson` сохраняют тот же хеш, что и в остальных строках лога. Как
следствие, настоящее значение ровно такой формы в `HASH`-поле попадёт в лог как есть.

## Конфигурация

```yaml
logging:
  masking:
    enabled: true
    mode: encoder                  # encoder или off
    use-default-fields: true
    additional-masked-fields: [internalNote, requesterAlias]
    additional-secret-headers: [X-Partner-Key]
    field-strategies:
      hash: [fullName, snils]
      last4: [contractNumber]
    scan-packages: [com.example.app] # Пусто: пакеты приложения
    max-body-size: 64KB             # Лимит для logMasker.maskJson(...)
```

| Свойство в `logging.masking` | По умолчанию | Значение |
|---|---|---|
| `enabled` | `true` | Глобальный выключатель |
| `mode` | `encoder` | `encoder` или `off`; неверное значение роняет старт контекста |
| `use-default-fields` | `true` | Включать имена и секретные заголовки из defaults |
| `additional-masked-fields` | Пусто | Дополнительные имена для маскирования |
| `additional-secret-headers` | Пусто | Дополнительные заголовки, всегда маскируются полностью |
| `field-strategies` | Пусто | Группы имён под `hash` или `last4` |
| `scan-packages` | Пакеты приложения | Пакеты для поиска `@Mask` |
| `max-body-size` | `64KB` | Пропускать слишком большие тела, переданные в `maskJson` |

## `@Mask`

```java
import io.github.laipan009.logmasking.core.Mask;
import io.github.laipan009.logmasking.core.MaskScope;
import io.github.laipan009.logmasking.core.MaskStrategy;

public record CustomerDto(
        @Mask String requesterAlias,
        @Mask(strategy = MaskStrategy.LAST4) String cardNumber,
        @Mask(scope = MaskScope.TYPED) String comment,
        String position) {}
```

Scope по умолчанию, `ALL`, индексирует имя глобально, в том числе в посторонних сообщениях лога.
`TYPED` исключает имя из глобального индекса и применяется только через
`logMasker.field(dto, CustomerDto::comment)`.

Аннотации читаются с полей, getter'ов и компонентов record. `@JsonProperty` и `@JsonNaming` добавляют в
индекс и Java-имя, и JSON-имя.

## `LogMasker`

Для явного маскирования внедрите `io.github.laipan009.logmasking.core.LogMasker`:

```java
log.info("request {}", logMasker.field(dto, Dto::getFullName));
log.info("request {} {}", logMasker.field(dto, Dto::getFullName),
        logMasker.field(dto, Dto::getSnils));
log.info("body {}", logMasker.field("snils", rawSnils));
log.info("response body: {}", logMasker.maskJson(rawJson));
```

`field(dto, Dto::getFullName)` принимает несвязанную ссылку на getter или accessor record, но не тело
лямбды и не связанную ссылку. Имена учитывают аннотации Jackson и стратегии именования. Стратегия из
`@Mask` на члене класса важнее глобальной политики поля.

Неизвестные имена маскируются полностью с одним предупреждением на место вызова. `null` сохраняется.
Чтобы снять неоднозначность перегрузок, приводите литерал null к `Object`:
`field("fullName", (Object) null)`. Возвращаемый объект вычисляет маску в `toString()`, откладывая
работу до форматирования лога.

Передавайте результат аргументом плейсхолдера, как в примерах выше. Encoder узнаёт его и оставляет
отрендеренную пару как есть: она не маскируется повторно, её стратегия не заменяется глобальной, а
текст сообщения после плейсхолдера остаётся видимым. Если склеить результат со строкой сообщения, он
снова становится обычным текстом, и к нему применяются границы обычного текста.

`maskJson` принимает сырой JSON, в том числе вывод, предназначенный не для SLF4J. Слишком большие или
некорректные тела становятся `body omitted`. Контекстные политики через
`MaskingPolicy.contextualMaskedFields` применяются только на этом JSON-пути, потому что им нужны
соседние поля.

## Производительность

Для локальных замеров запустите `./mvnw test -Pperf`. Эти тесты исключены из обычной сборки и не имеют
машинно-зависимых порогов прохождения. Результаты зависят от JVM, железа и формата логов; это не SLA
для production.

## Ограничения

- Appender'ы без поддерживаемых encoder'ов, включая `SocketAppender`, `SyslogAppender` и
  `SMTPAppender`, не покрываются. Стартер сообщает о них при запуске.
- Вывод до инициализации Logback не маскируется. Между инициализацией и готовностью контекста
  применяются только общие defaults; собственные свойства, бины и аннотации fallback'у ещё недоступны.
- Структурные сообщения сканируются внутри объемлющей JSON-строки. JSON, экранированный внутри другой
  JSON-строки, может остаться нераспознанным, потому что его ключи содержат экранированные кавычки.
- Внутренний сбой маскирования пропускает исходную строку без изменений и без рекурсивного сообщения в
  лог. Это сохраняет логирование, но может раскрыть чувствительные значения.
- Совпадение идёт по настроенному имени, а не по распознаванию произвольных персональных данных или
  форматов секретов.

## Участие, сопровождение и лицензия

См. [CONTRIBUTING.md](CONTRIBUTING.md), [архитектуру](docs/architecture.ru.md),
[roadmap](docs/roadmap.md), [руководство по релизам и внутреннему внедрению](docs/releasing.md),
[настройку GitHub](docs/github-setup.md) и [политику безопасности](SECURITY.md).

Распространяется по [лицензии MIT](LICENSE).
