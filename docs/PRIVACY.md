# Surge Media — Privacy Policy / Политика конфиденциальности

## English

Last updated: 2026-10-19

Surge Media is a rutracker search client and torrent downloader. This policy
explains what the app stores on your device, what leaves it, and how to delete
everything.

### 1. What the app stores on your device

- **Rutracker session cookies** — required to stay logged in to the mirror you
  configured. On Android they are encrypted at rest with an Android Keystore
  key; on desktop they live in a `chmod 600` file readable only by your user.
- **Settings** — your mirror, language, theme, Wi-Fi-only download preference
  and concurrency limit.

Nothing else is stored, and nothing leaves the device except the traffic
described below.

### 2. Network traffic

- **Search queries and the login page** go to the rutracker mirror you
  configured in Settings.
- **Torrent metadata and peer traffic** go to the trackers and peers of the
  torrents you choose to download — the ordinary traffic of any BitTorrent
  client.

The app contains **no third-party analytics, no advertising, no crash
reporting and no telemetry SDKs**.

### 3. Permissions

- `INTERNET` / `ACCESS_NETWORK_STATE` — network access and connectivity checks
  (e.g. the Wi-Fi-only setting).
- `FOREGROUND_SERVICE` (+ `FOREGROUND_SERVICE_DATA_SYNC`) — the download
  service and its notification.
- `POST_NOTIFICATIONS` — download progress notifications.
- `READ_MEDIA_AUDIO` / `READ_MEDIA_VIDEO` / `READ_MEDIA_IMAGES` — showing your
  own downloaded media in the Downloaded tab.

### 4. No accounts, no data sale, deletion = uninstall

The app has no accounts and sells no data. Deleting the app deletes its data:
app-private storage (cookies, settings) is removed by Android on uninstall.
Downloaded media stays in your public folders (`Music/`, `Movies/`, …) until
you delete it yourself.

### 5. Contact

Questions about this policy: **surge7@example.com**

<!-- PLACEHOLDER: replace surge7@example.com with the real support address
     before publishing this policy to RuStore / the GitHub listing. -->

---

## Русский

Обновлено: 2026-10-19

Surge Media — поисковый клиент rutracker и торрент-загрузчик. Эта политика
объясняет, какие данные приложение хранит на вашем устройстве, что покидает
устройство и как всё удалить.

### 1. Что приложение хранит на устройстве

- **Сессионные cookie rutracker** — нужны, чтобы оставаться в системе на
  настроенном вами зеркале. На Android они шифруются при хранении ключом
  Android Keystore; на настольных платформах лежат в файле с правами
  `chmod 600`, доступном только вашему пользователю.
- **Настройки** — зеркало, язык, тема, загрузка только по Wi-Fi и лимит
  одновременных загрузок.

Больше ничего не хранится, и ничто не покидает устройство, кроме трафика,
описанного ниже.

### 2. Сетевой трафик

- **Поисковые запросы и страница входа** отправляются на зеркало rutracker,
  настроенное вами в настройках.
- **Метаданные торрентов и пиринговый трафик** идут к трекерам и пирам тех
  торрентов, которые вы решили скачать, — обычный трафик любого
  BitTorrent-клиента.

В приложении **нет сторонней аналитики, рекламы, отчётов о сбоях и
телеметрических SDK**.

### 3. Разрешения

- `INTERNET` / `ACCESS_NETWORK_STATE` — доступ к сети и проверка соединения
  (например, для режима «только по Wi-Fi»).
- `FOREGROUND_SERVICE` (+ `FOREGROUND_SERVICE_DATA_SYNC`) — служба загрузок и
  её уведомление.
- `POST_NOTIFICATIONS` — уведомления о ходе загрузок.
- `READ_MEDIA_AUDIO` / `READ_MEDIA_VIDEO` / `READ_MEDIA_IMAGES` — показ вашей
  собственной скачанной медиатеки на вкладке «Скачанное».

### 4. Без аккаунтов, без продажи данных, удаление = деинсталляция

В приложении нет аккаунтов, данные никому не продаются. Удаление приложения
удаляет его данные: закрытое хранилище приложения (cookie, настройки)
удаляется системой Android при деинсталляции. Скачанные файлы остаются в ваших
публичных папках (`Music/`, `Movies/`, …), пока вы не удалите их сами.

### 5. Контакты

Вопросы по этой политике: **surge7@example.com**

<!-- PLACEHOLDER: замените surge7@example.com на настоящий адрес поддержки
     перед публикацией этой политики в RuStore / в описании на GitHub. -->
