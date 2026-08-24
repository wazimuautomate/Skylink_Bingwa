# Skylink Bingwa — Migration & Cutover Guide (Legacy API → Admin V2)

Admin V2 is built **beside** the existing `server/mybingwa-api`. The legacy app and its
data are preserved and keep serving the in-testing app until you deliberately cut over.

---

## 1. Read-only inventory of the current system

Legacy tables (unprefixed, owned by the payment API):

| Table | Owner | Admin V2 use |
|---|---|---|
| `payments` | payment API (writes) | **read-only** in the Payments page |
| `offers` | legacy admin | imported into `mb_offers` |
| `settings` | legacy admin | imported into `mb_support_config` |
| `templates` | legacy admin | imported into `mb_message_templates` |

Admin V2 creates its own `mb_*` tables — **no collision** with the above.

---

## 2. Back up first

In cPanel → **phpMyAdmin** → select the database → **Export** → Quick / SQL. Keep the
`.sql` backup before importing or cutting over.

---

## 3. Import legacy offers / settings / templates

From SSH in `server/admin-v2`:

```bash
php bin/import_legacy.php          # DRY RUN — shows exactly what it would import
php bin/import_legacy.php --apply   # perform the idempotent import
```

Then review in the admin (Offers / Support / Message templates) and **Publish** to create
the first snapshot. The importer never modifies legacy tables and is safe to re-run.

Mapping applied:
- `offers.daily_rule = ONCE_PER_DAY` → `mb_offers.daily_rule = ONCE_PER_RECIPIENT_PER_DAY`
- `offers.active = 0` → `status = draft`, else `active`
- `settings.{till,paybill,support,whatsapp}` → `mb_support_config`
- `templates.ttype = low_balance` → `purpose = low_balance`, sender → `mb_message_sender_ids`

---

## 4. Side-by-side staging

Admin V2 at `/admin` does not affect the live app or payment endpoints. Use it to publish
and verify (`/admin/api/v1/app/manifest`, `/admin/api/v1/health`) with **zero** impact on
production. Nothing about the customer app changes until step 6.

---

## 5. Payment gateway settings from the dashboard (opt-in bridge)

Admin V2 → **Support → Payment gateway** (Super Admin) manages the server-side payment /
delivery settings (Till route, Paybill route, fulfilment phone, business name, SMS
provider — key encrypted). These are **not** app config and are never synced to the app.

To let the **live payment API** read them, enable the bridge. Edit the legacy
`server/mybingwa-api/config.php` and, just before `return $config;`, add:

```php
$gw = @include __DIR__ . '/../admin-v2/cutover/gateway_bridge.php';
if (is_array($gw)) {
    foreach (['transaction_type','business_shortcode','party_b','paybill_shortcode',
              'callback_url','fulfilment_phone','business_name','sms_api_url',
              'sms_sender_id','sms_api_key','daraja_env'] as $k) {
        if (isset($gw[$k]) && $gw[$k] !== '') { $config[$k] = $gw[$k]; }
    }
}
```

Adjust `'/../admin-v2/…'` to the real relative path between the two folders. The bridge
returns `[]` on any error, so a misconfigured admin can **never** break payments. To
revert, delete those lines. The deepest Daraja secrets (consumer key/secret, passkey) stay
in `config.php`.

---

## 6. Cut the app over to published snapshots (optional, later)

The shipped app calls `get_offers.php` / `get_config.php` at the host root. Two options:

**A. Point the app at the new API (needs an app release).** Change the app's base URL to
`/admin/` and switch to `/api/v1/app/offers` etc. Requires a new `versionCode`.

**B. Make the legacy endpoints serve published data (no app release).** Replace the bodies
of `get_offers.php` / `get_config.php` with a version that reads the current published
snapshot from `mb_configuration_releases`, keeping the legacy `X-App-Key` check and JSON
shapes. Minimal example for `get_offers.php`:

```php
<?php
$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';
require_app_key($config);
$gw = @include __DIR__ . '/../admin-v2/cutover/snapshot_offers.php'; // boots admin-v2, returns the compat array
json_out(['offers' => is_array($gw) ? $gw : []]);
```

Keep the legacy files backed up; this change is fully reversible. Do this only after
verifying the published snapshot in staging.

---

## 7. Production cutover checklist

- [ ] Database backed up.
- [ ] `import_legacy.php --apply` run and reviewed.
- [ ] First snapshot published and signed; `/api/v1/health` green.
- [ ] Gateway bridge enabled and a **test payment** confirmed end-to-end.
- [ ] (If chosen) legacy endpoints serve published data; app still parses them.
- [ ] Rollback steps rehearsed (below).

---

## 8. Rollback plan

- **Config rollback:** in Admin V2 → Release history → open an earlier version → Roll back
  (reason + re-auth). This publishes a new later version with the old contents; old
  snapshots are never mutated.
- **Gateway bridge rollback:** delete the added lines from legacy `config.php`.
- **Endpoint rollback:** restore the backed-up legacy `get_*.php` files.
- **Full rollback:** the legacy admin (`server/mybingwa-api/admin`) still works against the
  legacy tables, which the import never modified.

---

## 9. Immediate payment fix — "The receiver party information is invalid"

This error is unrelated to Admin V2; it is a Daraja routing mismatch in the legacy
`server/mybingwa-api/config.php`. You currently have:

```php
'business_shortcode' => '4050595',
'party_b'            => '4063396',
'transaction_type'  => 'CustomerPayBillOnline',
```

**Why it fails:** a **Paybill** STK (`CustomerPayBillOnline`) requires
`BusinessShortCode` **==** `PartyB` (the same paybill number). Yours differ
(`4050595` ≠ `4063396`), so Daraja rejects the receiver party. But buy-for-myself is a
**Till** (Buy Goods) — that uses `CustomerBuyGoodsOnline`, not Paybill.

**If buy-for-myself is a Till (Buy Goods) — the expected setup:**

```php
'transaction_type'   => 'CustomerBuyGoodsOnline',
'business_shortcode' => '<Head-Office / store number for the STK password>',
'party_b'            => '<Till number that RECEIVES the money, e.g. 4063396>',
// 'passkey' stays the Lipa na M-Pesa Online passkey for that shortcode
```

For Buy Goods, `BusinessShortCode` is the store/Head-Office number Safaricom issued with
your STK passkey, and `PartyB` is the till that receives the money. If Safaricom gave you a
single number for both, set them equal.

**If it is really a Paybill:** set `business_shortcode` and `party_b` to the **same**
paybill number and keep `CustomerPayBillOnline` with that paybill's passkey.

Buy-for-another (Paybill) uses `paybill_shortcode` separately and is already handled by the
code. After editing, retry a small live payment. Once the gateway bridge (step 5) is
enabled you can make these edits from **Support → Payment gateway** instead of the file.
