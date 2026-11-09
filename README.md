# ororotv_bot

Telegram bot that quizzes you on the words saved in your [ororo.tv](https://ororo.tv/en) dictionary.
It sends a random word (with the subtitle line you saved it from), you reply with the translation, and the bot
reacts (👍 / 👎) and shows the answer.

## Two ways to load words

1. **📚 Connect ororo.tv** (one tap after the first time). A Telegram Mini App asks for the ororo.tv email and
   password once, keeps them in the phone's secure storage (Telegram SecureStorage), and calls
   `https://front.ororo.tv/api/v2/dictionary` **directly from the phone**. Only the word list is sent to the bot
   server; the password never leaves the device. Next time the app loads the words as soon as it opens.
2. **📥 Download dictionary file** (for Google-only accounts). Opens `https://ororo.tv/en/users/export_dictionary`
   in the browser where the user is signed in; they send the downloaded file to the bot.
   Google users can also set an ororo password once ("Forgot your password?") and then use option 1.

The bot stores no passwords or cookies; see "Data and statistics" below for what it does keep.

## Run

Needs JDK 21+ and Maven. Settings are read as `-DNAME=value` system properties, or environment variables.

| Setting | Meaning |
|---|---|
| `TELEGRAM_BOT_TOKEN` | token from [@BotFather](https://t.me/BotFather) (required) |
| `WEBAPP_URL` | public `https://` address that forwards to the Mini App server; without it only option 2 is shown |
| `WEBAPP_PORT` | local port of the Mini App server (default `8080`) |
| `BOT_DATA_DIR` | folder of the SQLite database `bot.db` (default `data`) |
| `MAX_NEW_WORDS_PER_DAY` | new words one user may add per rolling 24 hours (default `100`) |
| `MAX_IMPORTS_PER_DAY` | imports (Mini App or file) one user may do per rolling 24 hours (default `10`) |
| `ADMIN_USER_IDS` | comma-separated Telegram user IDs allowed to use `/admin` (send `/admin` once to see your ID) |
| `ANTHROPIC_API_KEY` | optional: translate words with Claude, using the subtitle line as context |
| `TRANSLATION_LANG` | translation language if the data doesn't say (default `ru`) |

Telegram only opens Mini Apps over HTTPS. For local development, a free Cloudflare quick tunnel works:

```bash
cloudflared tunnel --url http://localhost:8080
```

It prints an address like `https://something.trycloudflare.com`. Then start the bot with it:

```bash
mvn compile exec:java -DTELEGRAM_BOT_TOKEN=123456:ABC... -DWEBAPP_URL=https://something.trycloudflare.com
```

Quick tunnels get a new address each run; for real users, host the bot on a server with a fixed HTTPS domain.

## Commands

| Command | What it does |
|---|---|
| `/start`, `/help` | welcome message with the two load buttons |
| `/quiz` | ask a word (words you get wrong come up more often) |
| `/skip` | reveal the answer and move on (counts as not known) |
| `/stats` | your progress: today / all time, accuracy, streak, hardest words, words left for today |
| `/sync` | the load buttons again, to refresh |
| `/admin` | bot report for admins: users, imports, answers, most active users, recent imports |
| (send a file) | import a dictionary export file |

## Data and statistics

Everything lives in one SQLite file, `data/bot.db` (git-ignored). Back it up by copying it while the bot is stopped.
An old `data/state.json` is moved into it automatically on first start (and renamed to `state.json.migrated`).

| Table | What's in it |
|---|---|
| `users` | Telegram ID, username, first name, first and last seen |
| `words` | each user's words with translation, subtitle line, when added, right/wrong counters; words removed from ororo.tv are kept (inactive) with their stats |
| `imports` | every import: when, Mini App or file, words received / added / not translated / over the limit, translator used |
| `answers` | every answer: user, word, time, correct / wrong / skipped |

**Limits** (per user, rolling 24 hours): at most `MAX_NEW_WORDS_PER_DAY` new words are added; extra words are added
on a later reload, and the bot says when. Reloading words you already have is free. At most `MAX_IMPORTS_PER_DAY`
imports, and only one at a time.

**Monitoring:** the console logs every new user (`[user]`), every import (`[import]`) and every limit hit (`[limit]`).
For ad-hoc questions, open the database with any SQLite tool, e.g. `sqlite3 data/bot.db "SELECT * FROM imports ORDER BY at DESC LIMIT 10"`.

## Notes

- `/api/v2/dictionary` is not documented by ororo.tv. It answers `{"dictionary":[{"word":..,"context":..,"string":{..}}]}`
  with HTTP Basic auth and allows cross-origin calls, which is what makes option 1 possible. It may change without notice.

## Translations

The ororo.tv API returns words without translations (`"string":{}`), so the bot translates them itself, in the
background, right after an import:

- **Claude** (when `ANTHROPIC_API_KEY` is set): sends each word with its subtitle line, so the sense fits
  ("Can't help it" -> "не могу удержаться", not "помощь"), and returns several acceptable answers.
- **MyMemory** (free, no key): word-by-word machine translation, one or two variants. Used when there is no key,
  or for words Claude didn't return.

Translations are kept in the database and reused on the next import, so each word is translated only once.
Words that still have no translation are skipped (and retried on the next import); the bot says how many.

## Deploy to Railway

The repo is ready for [Railway](https://railway.com): `Dockerfile` builds one self-contained jar, `railway.json` tells
Railway to use it, health-check `/health`, and restart the bot if it crashes.

1. **Push** the latest code to GitHub.
2. Railway → **New Project → Deploy from GitHub repo** → pick `ororotv_bot`. The first build takes a few minutes.
3. Service → **Variables**: add
   - `TELEGRAM_BOT_TOKEN` = your bot token
   - `BOT_DATA_DIR` = `/data`
   - optional: `ADMIN_USER_IDS`, `ANTHROPIC_API_KEY`, `MAX_NEW_WORDS_PER_DAY`, `MAX_IMPORTS_PER_DAY`
4. Service → right-click → **Attach Volume**, mount path **`/data`**. Without it the database is wiped on every deploy.
5. Service → **Settings → Networking → Generate Domain**. Railway gives an address like
   `https://ororotvbot-production.up.railway.app` (if it asks for a port, use the one shown in the deploy logs:
   "Mini App served on port …").
6. Add the variable `WEBAPP_URL` = that `https://…` address. Railway redeploys; the **Words** button now opens the
   Mini App from Railway. No more cloudflared.
7. **Stop the bot in IntelliJ.** Two copies with the same token make Telegram reject both (`409 Conflict`).

**Keeping your current data (optional):** stop the local bot first, then copy `data/bot.db` into the volume, e.g. with the
Railway CLI (`railway ssh`) or by temporarily adding an import step. Otherwise the server starts with an empty database.

Every push to GitHub redeploys automatically. Logs (`[user]`, `[import]`, `[limit]`) are in the service's
**Deployments → View logs**. Back up the database from time to time (see `NICE_TO_HAVE.md`).
