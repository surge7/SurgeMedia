# Surge Media — How to sign in / Как войти в аккаунт

## English

The tracker sits behind a Cloudflare browser check, so Surge Media cannot log
in with a username and password. Instead it reuses a session from your normal
browser:

- **Android** — just tap **Sign in**. The app opens the tracker in an embedded
  browser; you type your credentials, it solves Cloudflare, and the session is
  saved automatically. Nothing on this page is needed.
- **Desktop, Chromium browser (Chrome, Edge, Yandex Browser, Brave)** — click
  **Sign in via browser** in the app. A normal browser window opens on the
  tracker's login page; sign in there, and the session returns to the app
  automatically (see "Automatic sign-in" below).
- **Desktop, Safari or Firefox** — use manual import: sign in in your browser
  and copy two values into the app. This page explains how, **for each
  browser**.

### Automatic sign-in (desktop, one click)

1. In the app click **Sign in via browser**.
2. A browser window opens directly on the tracker's login page. Sign in as
   usual — including any Cloudflare check.
3. That's it. The moment the login cookie appears, the app picks up the
   session (paired with that browser's own User-Agent), the window closes
   itself, and the app validates the session.

Details worth knowing:

- The app launches the browser with a **throwaway temporary profile** — your
  normal profile, bookmarks and cookies are never touched, and everything the
  login leaves behind is deleted afterwards.
- The session is read through the browser's standard developer interface
  (a local debug port opened for this window only) — nothing is sent
  anywhere; the conversation stays on `127.0.0.1`.
- The Cookie and User-Agent always come from the **same browser window**, so
  the Cloudflare `cf_clearance` binding is satisfied by construction — the
  classic "works in browser, rejected in app" mismatch cannot happen.
- If the button says no Chromium browser was found, install Chrome/Edge/Yandex
  or use manual import below.

### Manual import (desktop, Safari/Firefox or no Chromium browser)

Used when the automatic flow above isn't available. You copy the Cookie
header and User-Agent by hand; the same-browser rule then matters.

#### The three rules (read first)

1. **Sign in first.** Log into the tracker in your browser *before* copying
   anything, otherwise the cookie has no session in it.
2. **Cookie and User-Agent must come from the same browser.** Cloudflare binds
   its `cf_clearance` cookie to the exact User-Agent string it was issued for.
   A cookie copied from Chrome with a User-Agent copied from Firefox will be
   rejected. Copy both from the same request, in the same browser.
3. **Copy the full raw value, on one line.** The Cookie value is long — it
   looks like `bb_guid=…; bb_ssl=…; bb_session=…; cf_clearance=…`. Copy all of
   it, without the `Cookie:` prefix and without line breaks.

### Chrome, Edge, Yandex Browser, Brave, Opera (Chromium)

1. Sign in to the tracker in the browser.
2. Open DevTools: press **F12** (Windows/Linux) or **⌥⌘I** (macOS), or menu →
   *More tools* → *Developer tools*.
3. Switch to the **Network** tab.
4. Reload the page (**F5** / **⌘R**) with DevTools open.
5. Click the **first row** in the request list — the page document itself.
6. In the side panel open **Headers** and scroll to **Request Headers**.
7. Find **`cookie:`**, right-click its value → **Copy value**. Paste it into
   the app's *Cookie header* field.
8. Find **`user-agent:`** in the same list and copy its value too (one line).
   Paste it into the *User-Agent* field.

> Tip: the User-Agent is also shown in the DevTools **Console** tab — type
> `navigator.userAgent` and press Enter.

### Firefox

1. Sign in to the tracker.
2. Open DevTools: **F12** or **⌥⌘I**, or menu ☰ → *More Tools* →
   *Web Developer Tools*.
3. Switch to the **Network** tab and reload the page. If the list is empty,
   click the reload icon inside the Network panel or reload again.
4. Click the **first request** (the document).
5. In the **Headers** panel scroll to **Request Headers**.
6. Right-click the **`cookie`** value → **Copy Value** ("Copy" → "Value" on
   some versions). Paste it into *Cookie header*.
7. Copy the **`user-agent`** value from the same list into *User-Agent*
   (or `navigator.userAgent` in the Console tab).

### Safari (macOS)

Safari has no F12 — the developer tools must be enabled once:

1. One-time setup: Safari → **Settings…** (**⌘,**) → **Advanced** tab →
   check **"Show features for web developers"** (on older macOS:
   *"Show Develop menu in menu bar"*). Close Settings.
2. Sign in to the tracker.
3. Menu bar → **Develop** → **Show Web Inspector** (**⌥⌘I**).
4. Switch to the **Network** tab and reload the page.
5. Click the **first request** (the document) in the list on the left.
6. Open **Headers** → scroll to **Request Headers**.
7. Select the **`cookie`** value completely and copy it (⌘C). Paste it into
   *Cookie header*.
8. Copy the **`user-agent`** value from the same list into *User-Agent*
   (or `navigator.userAgent` in the Console tab).

### What a good Cookie looks like

    bb_guid=…; bb_ssl=…; bb_session=…; cf_clearance=…

- `bb_session=…` — you are logged in. Missing → you copied before signing in.
- `cf_clearance=…` — the Cloudflare check was passed. Missing → open the site
  once more, pass the check, then copy the cookie again.

### Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| AUTH error right after import | Cookie and User-Agent from different browsers, or cookie truncated | Copy **both** again, from the same request, in the same browser |
| 403 / endless Cloudflare loop | `cf_clearance` missing or expired | Pass the check in the browser, re-copy |
| Works in the browser, fails in the app | Line break snuck into the pasted cookie | Re-paste as one single line |
| Worked for days, then stopped | Session simply expired | Re-import fresh values |

### Security note

The cookie you paste is **full access to your tracker account**. Paste it only
into Surge Media, never send it to anyone. On Android it is stored encrypted
with a hardware key (Android Keystore); on desktop it lives in a `chmod 600`
file readable only by your user.

---

## Русский

Трекер находится за браузерной проверкой Cloudflare, поэтому Surge Media не
может войти по логину и паролю. Вместо этого приложение переиспользует сессию
из вашего обычного браузера:

- **Android** — просто нажмите **«Войти»**. Приложение откроет трекер во
  встроенном браузере: вы вводите логин и пароль, проверка Cloudflare
  проходится автоматически, сессия сохраняется сама. Ничто на этой странице
  вам не нужно.
- **Компьютер, браузер на Chromium (Chrome, Edge, Яндекс Браузер, Brave)** —
  нажмите в приложении **«Войти через браузер»**. Откроется обычное окно
  браузера на странице входа трекера; войдите в нём — и сессия вернётся в
  приложение автоматически (см. «Автоматический вход» ниже).
- **Компьютер, Safari или Firefox** — используйте ручной импорт: войдите на
  трекер в браузере и скопируйте в приложение два значения. Ниже — как именно,
  **для каждого браузера**.

### Автоматический вход (компьютер, одна кнопка)

1. В приложении нажмите **«Войти через браузер»**.
2. Откроется окно браузера прямо на странице входа трекера. Войдите как
   обычно — включая проверку Cloudflare, если она появится.
3. Всё. Как только появляется cookie входа, приложение забирает сессию (в паре
   с User-Agent того же браузера), окно закрывается само, а приложение
   проверяет сессию.

Что полезно знать:

- Приложение запускает браузер с **одноразовым временным профилем** — ваш
  обычный профиль, закладки и cookie не затрагиваются, а всё, что осталось
  после входа, удаляется.
- Сессия читается через штатный интерфейс разработчика браузера (локальный
  debug-порт, открытый только для этого окна) — никуда ничего не отправляется,
  весь обмен идёт по `127.0.0.1`.
- Cookie и User-Agent всегда берутся из **одного и того же окна браузера**, поэтому
  привязка `cf_clearance` Cloudflare к User-Agent соблюдается автоматически —
  классическое «в браузере работает, в приложении нет» здесь невозможно.
- Если кнопка сообщает, что браузер на Chromium не найден — установите
  Chrome/Edge/Яндекс Браузер или используйте ручной импорт ниже.

### Ручной импорт (компьютер, Safari/Firefox или нет браузера на Chromium)

Применяется, когда автоматический вход недоступен: вы копируете заголовок
Cookie и User-Agent вручную — и здесь важно правило «одного браузера».

#### Три главных правила (прочтите сначала)

1. **Сначала войдите.** Авторизуйтесь на трекере в браузере *до* копирования,
   иначе в cookie не будет сессии.
2. **Cookie и User-Agent — из одного и того же браузера.** Cloudflare привязывает
   cookie `cf_clearance` к конкретной строке User-Agent, для которой она выдана.
   Cookie из Chrome с User-Agent из Firefox работать не будет. Копируйте оба
   значения из одного запроса, в одном браузере.
3. **Копируйте значение целиком, одной строкой.** Значение Cookie длинное —
   вида `bb_guid=…; bb_ssl=…; bb_session=…; cf_clearance=…`. Скопируйте его
   полностью, без префикса `Cookie:` и без переносов строк.

### Chrome, Edge, Яндекс Браузер, Brave, Opera (Chromium)

1. Войдите на трекер в браузере.
2. Откройте инструменты разработчика: **F12** (Windows/Linux) или **⌥⌘I**
   (macOS), либо меню → «Дополнительные инструменты» → «Инструменты
   разработчика».
3. Перейдите на вкладку **Network** («Сеть»).
4. Обновите страницу (**F5** / **⌘R**) при открытых инструментах.
5. Кликните **первую строку** в списке запросов — сам документ страницы.
6. В боковой панели откройте **Headers** и прокрутите до **Request Headers**
   («Заголовки запроса»).
7. Найдите **`cookie:`**, кликните по значению правой кнопкой → **Copy value**
   («Копировать значение»). Вставьте его в поле *Заголовок Cookie*.
8. В том же списке найдите **`user-agent:`** и скопируйте его значение (одной
   строкой). Вставьте в поле *User-Agent*.

> Подсказка: User-Agent виден и во вкладке **Console** — наберите
> `navigator.userAgent` и нажмите Enter.

### Firefox

1. Войдите на трекер.
2. Откройте инструменты: **F12** или **⌥⌘I**, либо меню ☰ → «Другие
   инструменты» → «Инструменты разработчика».
3. Перейдите на вкладку **Network** («Сеть») и обновите страницу. Если список
   пуст — кликните значок обновления внутри панели Network или обновите
   страницу ещё раз.
4. Кликните **первый запрос** (документ).
5. В панели **Headers** прокрутите до **Request Headers**.
6. Правой кнопкой по значению **`cookie`** → **Copy Value** («Копировать» →
   «Значение» в некоторых версиях). Вставьте в поле *Заголовок Cookie*.
7. Скопируйте значение **`user-agent`** из того же списка в поле *User-Agent*
   (или `navigator.userAgent` во вкладке Console).

### Safari (macOS)

В Safari нет F12 — инструменты разработчика включаются один раз:

1. Однократная настройка: Safari → **Настройки…** (**⌘,**) → вкладка
   **«Дополнения»** → включите **«Показывать функции для веб-разработчиков»**
   (на старых macOS: *«Показывать меню „Разработка“ в строке меню»*).
   Закройте настройки.
2. Войдите на трекер.
3. Строка меню → **«Разработка»** → **«Показать веб-инспектор»** (**⌥⌘I**).
4. Перейдите на вкладку **Network** («Сеть») и обновите страницу.
5. Кликните **первый запрос** (документ) в списке слева.
6. Откройте **Headers** и прокрутите до **Request Headers**.
7. Выделите значение **`cookie`** полностью и скопируйте (⌘C). Вставьте в поле
   *Заголовок Cookie*.
8. Скопируйте значение **`user-agent`** из того же списка в поле *User-Agent*
   (или `navigator.userAgent` во вкладке Console).

### Как выглядит правильный Cookie

    bb_guid=…; bb_ssl=…; bb_session=…; cf_clearance=…

- `bb_session=…` — вы авторизованы. Нет — вы скопировали cookie до входа.
- `cf_clearance=…` — проверка Cloudflare пройдена. Нет — откройте сайт ещё
  раз, пройдите проверку и скопируйте cookie заново.

### Если что-то не работает

| Симптом | Вероятная причина | Решение |
| --- | --- | --- |
| Ошибка AUTH сразу после импорта | Cookie и User-Agent из разных браузеров либо cookie скопирован не полностью | Скопируйте **оба** значения заново — из одного запроса, в одном браузере |
| 403 / бесконечная проверка Cloudflare | `cf_clearance` отсутствует или истёк | Пройдите проверку в браузере и скопируйте заново |
| В браузере работает, в приложении нет | В вставленный cookie попал перенос строки | Вставьте заново одной строкой |
| Работало несколько дней, потом перестало | Сессия просто истекла | Импортируйте свежие значения |

### О безопасности

Вставляемый cookie — это **полный доступ к вашему аккаунту на трекере**.
Вставляйте его только в Surge Media и никому не пересылайте. На Android он
хранится зашифрованным аппаратным ключом (Android Keystore), на компьютере — в
файле с правами `chmod 600`, доступном только вашему пользователю.
