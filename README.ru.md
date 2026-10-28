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
    <version>0.3.2-SNAPSHOT</version>
</dependency>
```

Defaults покрывают конкретные имена вроде `fullName`, `email`, `phone`, `passportNumber`, `password` и
`accessToken`, а также секретные заголовки. Общие имена вроде `comment`, `amount`, идентификаторов и
полей конкретного бизнеса нужно настраивать явно.

## Как это работает

Стартер встраивается в Logback в двух местах, поэтому код приложения не меняется, а маскирование
покрывает весь вывод: сообщения библиотек, stack trace и MDC. Изменения между версиями — в
[CHANGELOG](CHANGELOG.md).

**В источнике.** Турбо-фильтр Logback вызывается раньше, чем создаётся событие, на любом пути: вызов
SLF4J, fluent API, мосты JCL, JUL и Log4j. Фильтр сам собирает событие уже замаскированным и отдаёт
его appender'ам; исходное событие не создаётся. Поэтому замаскированные данные получают все, кто
читает события мимо encoder'а: appender'ы без encoder'а (`SocketAppender`, Sentry) и Java-агенты
(OpenTelemetry снимает событие на входе в `callAppenders`, до всех appender'ов). Замаскированы текст
сообщения, MDC, key-value пары fluent API и сообщения исключений по всей цепочке `cause` и
`suppressed`. Аргументов у события нет: они не замаскированы. Тем, кто достаёт из события сам
`Throwable` (Sentry, OpenTelemetry), достаётся его копия с теми же масками; исключение, в цепочке
которого маскировать нечего, остаётся исходным.

**В encoder'е.** Обёртка вокруг родного encoder'а каждого appender'а маскирует отрендеренную строку и
ловит то, что encoder добавил к событию сам. Она одинаково работает с текстовыми и структурными
encoder'ами; JSON-тело в сообщении к этому моменту уже замаскировано в источнике, до того как
JSON-encoder экранирует кавычки.

```text
DefaultMaskingPolicy ----+
PropertiesMaskingPolicy -+
MaskFieldIndex (@Mask) --+--> MaskingPolicyRegistry --> LineMasker (source, encoder) / LogMasker
Custom MaskingPolicy ---+

Application / libraries / JCL, JUL, Log4j --> SLF4J --> Logback
    --> MaskingTurboFilter (builds the event already masked) --> callAppenders
        --> Java agents (OpenTelemetry)
        --> appenders without an encoder, Sentry
        --> appender --> MaskingEncoder (delegate rendering + LineMasker) --> console / file / Logstash
```

Маскирование сохраняется, когда Logback пересобирается: после refresh Spring Cloud, после
перечитывания по `scan="true"`, а для appender'ов, добавленных из кода, — в течение
`recheck-interval`.

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
значение: `fullName=John Smith` маскируется целиком. Внутри скобок дампа запятая — граница, только
если за ней следующее поле: `Dto(address=Main St, Springfield, id=7)` превращается в
`Dto(address=***, id=7)`. В тексте сообщения вне скобок запятая — граница всегда, чтобы диагностика
после значения осталась видна: `fullName=Smith, John` превращается в `fullName=***, John`. Скобка
после пробела, как в `Not found (snils=..., reason: ...)`, дампом не считается. Парные скобки внутри
значения проходятся целиком, вложенный `AddressDto(...)` тоже. Непарная `(`, например `:(`, не
растягивает значение дальше следующего поля или скобки дампа, а непарная `)` значение обрывает, и
хвост остаётся открытым: `Dto(password=1) no address, 2) ...` превращается в
`Dto(password=***) no address, 2) ...` — из строки не видно, чья это скобка. У значений JSON в
кавычках своя обработка границ.

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
следствие, настоящее значение ровно такой формы в `HASH`-поле попадёт в лог как есть. В общем случае
значение, которое уже имеет форму своей стратегии, не меняется: сообщение проходит маскирование в
источнике и ещё раз в encoder'е, а неизменённая строка сохраняет исходные байты.

## Конфигурация

```yaml
logging:
  masking:
    enabled: true
    mode: encoder                  # encoder (маскирование в источнике и encoder'е) или off
    use-default-fields: true
    additional-masked-fields: [internalNote, requesterAlias]
    additional-secret-headers: [X-Partner-Key]
    field-strategies:
      hash: [fullName, snils]
      last4: [contractNumber]
    scan-packages: [com.example.app] # Пусто: пакеты приложения
    max-body-size: 64KB             # Лимит для logMasker.maskJson(...)
    recheck-interval: 10s           # 0 выключает периодическую проверку
```

| Свойство в `logging.masking` | По умолчанию | Значение |
|---|---|---|
| `enabled` | `true` | Глобальный выключатель; читается на старте, refresh его не меняет |
| `mode` | `encoder` | `encoder` или `off`; неверное значение роняет старт контекста |
| `use-default-fields` | `true` | Включать имена и секретные заголовки из defaults |
| `additional-masked-fields` | Пусто | Дополнительные имена для маскирования |
| `additional-secret-headers` | Пусто | Дополнительные заголовки, всегда маскируются полностью |
| `field-strategies` | Пусто | Группы имён под `hash` или `last4` |
| `scan-packages` | Пакеты приложения | Пакеты для поиска `@Mask` |
| `max-body-size` | `64KB` | Пропускать слишком большие тела, переданные в `maskJson` |
| `recheck-interval` | `10s` | Как часто перепроверять фильтр в источнике и обёртки encoder'ов; `0` выключает проверку, иначе не меньше `1s` |

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

Маскирование в источнике проходит по сообщению ещё раз, до encoder'а. `MaskingSourcePerformanceTest`
измеряет весь вызов логгера, от `log.info` до байтов в потоке: без маскирования, только с encoder'ом
и с обоими слоями. Исключение с персональными данными с обоими слоями обходится дешевле, чем с одним
encoder'ом: stack trace приходит в encoder уже замаскированным, и строку не приходится пересобирать.
Цена растёт линейно с длиной строки, поэтому дороже всего большие тела.

## Ограничения

Полный список известных пробелов со статусами — в [known gaps](docs/known-gaps.md) (на английском).

- Оба слоя работают на любом appender'е с encoder'ом: консоль, файлы, сетевые appender'ы и
  appender'ы брокеров сообщений, в том числе вложенные в составные. Appender'ы без encoder'а
  (`SocketAppender`, `SyslogAppender`, `SMTPAppender`, `SiftingAppender`) получают замаскированное
  событие, но то, что они добавляют к нему сами, не маскируется; при запуске о них сообщает INFO
  `Log masking reaches ... only through events masked at the source`. Если фильтр поставить не
  удалось, остаётся только encoder, и при запуске пишется WARN `Log masking could not be attached to
  the event source`.
- У события нет аргументов, а `getMessage()` отдаёт готовый текст вместо шаблона. `StructuredArguments`
  Logstash не попадают в JSON отдельными полями (в тексте сообщения они остаются), а Sentry группирует
  события по тексту, а не по шаблону.
- Исключение, которое пришлось замаскировать, Sentry и OpenTelemetry получают копией типа
  `MaskedThrowable` (в OpenTelemetry это `exception.type`); настоящий класс — в первой строке stack
  trace. Исключения, в которых маскировать нечего, сохраняют настоящий тип.
- Fluent API SLF4J (`log.atInfo()...`) проходит через турбо-фильтры начиная с Logback 1.5.25. В более
  старом Logback, а также для событий, собранных мимо логгера (прямой `callAppenders` или `doAppend`),
  маскирует только encoder.
- Вызов без сообщения, например `log.error(e.getMessage())` у исключения без сообщения, фильтр не
  отличает от проверки `isErrorEnabled()`, и такое событие Logback собирает сам: его MDC appender'ы без
  encoder'а и агенты получают открытым. Encoder его маскирует.
- Место вызова (`%class`, `%method`, `%line`, атрибуты `code.*` в OpenTelemetry) событие определяет
  само: класс, который обёртка над `LocationAwareLogger` передаёт Logback, до фильтра не доходит.
  Границей считается класс, вызвавший `Logger.log`; если обёртка передаёт другой, её пакет нужно
  добавить в `<frameworkPackage>` в logback.xml.
- Вывод до инициализации Logback не маскируется. Между инициализацией и готовностью контекста
  применяются только общие defaults; собственные свойства, бины и аннотации fallback'у ещё недоступны.
- Refresh Spring Cloud с bootstrap (`/actuator/refresh`, Spring Cloud Bus) пересобирает Logback, если
  хотя бы один `PropertySourceLocator` что-то вернул и во временном окружении refresh есть ключ
  `logging.*`: из ответа config server'а, `bootstrap.yml`, `LOGGING_*`, `-Dlogging.*`, `--logging.*`
  или `defaultProperties`. Ключи из `application.yml` не в счёт: временный контекст его не читает.
  Ключи самого стартера тоже `logging.*`: если `logging.masking.*` приходят из config server'а, Logback
  пересобирается на каждом refresh. Маскирование возвращается на `EnvironmentChangeEvent`, в логе это
  повторная строка `Log masking is attached...` прямо перед `Refresh keys changed`. Если Logback
  собран без XML, строки других потоков в миллисекунды между пересборкой и возвратом маскирования не
  маскируются; у XML-конфигурации такого окна нет (см. ниже).
- `enabled` и `mode` читаются один раз, на старте. Refresh восстанавливает решение старта и не
  пересматривает его: включить или выключить маскирование можно только рестартом.
- Перечитанная XML-конфигурация (`scan="true"`, запасная конфигурация после ошибки разбора,
  `logback-spring.xml` при refresh) получает обёртки сразу, в потоке конфигурации: стартер подписан на
  событие конца конфигурации Logback и пишет `Log masking is attached ... after Logback was
  reconfigured`. При refresh Spring глушит лог на время пересборки Logback, поэтому строка та же, что
  и без XML, — перед `Refresh keys changed`. Appender'ы, добавленные из кода, накрывает периодическая
  проверка раз в `recheck-interval`. До неё их строки не маскируются, а потом в логе появляется WARN
  `Log masking re-attached...`. Если проход падает, пишется один WARN `Log masking re-attach failed...`.
  Проход без изменений стоит десятки микросекунд на сотни логгеров.
- Структурные encoder'ы (Logstash, `logging.structured.format`) поддерживаются: JSON-тело в сообщении
  маскируется в источнике до экранирования, а сканер encoder'а работает внутри строки сообщения и не
  выносит маску за закрывающую кавычку. JSON, экранированный ещё до SLF4J, например JSON-строка внутри
  JSON-тела, может остаться нераспознанным.
- Сбой маскирования в источнике не роняет запрос: текст сообщения заменяется на
  `[message suppressed: log masking failed]`, и в лог один раз пишется WARN `Log masking failed...`.
  Сбой в encoder'е по-прежнему пропускает строку без изменений и молча: это сохраняет логирование, но
  может раскрыть чувствительные значения.
- Совпадение идёт по настроенному имени, а не по распознаванию произвольных персональных данных или
  форматов секретов.

## Участие, сопровождение и лицензия

См. [CONTRIBUTING.md](CONTRIBUTING.md), [архитектуру](docs/architecture.ru.md),
[roadmap](docs/roadmap.md), [известные пробелы](docs/known-gaps.md), [CHANGELOG](CHANGELOG.md),
[руководство по релизам и внутреннему внедрению](docs/releasing.md),
[настройку GitHub](docs/github-setup.md) и [политику безопасности](SECURITY.md).

Распространяется по [лицензии MIT](LICENSE).
