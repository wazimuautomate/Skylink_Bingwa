<?php
use App\Repositories\OfferRepository;
use App\Core\Session;
$old = Session::get('_old', []);
Session::forget('_old'); // consume repopulation data exactly once
$errs = $old['_errors'] ?? [];
$o = $offer ?? [];
// value(): prefer repopulated old input, then the loaded offer, then a default.
$val = function (string $key, $default = '') use ($old, $o) {
    if (array_key_exists($key, $old)) return $old[$key];
    return $o[$key] ?? $default;
};
$toLocal = function (?string $utc) {
    if (!$utc) return '';
    try { return (new DateTimeImmutable($utc, new DateTimeZone('UTC')))->setTimezone(new DateTimeZone('Africa/Nairobi'))->format('Y-m-d\TH:i'); }
    catch (Throwable $e) { return ''; }
};
// A stored TIME ("17:00:00") for an <input type="time">, which wants "HH:MM".
$toClock = function ($t) {
    $text = trim((string) ($t ?? ''));
    if ($text === '') return '';
    return substr($text, 0, 5);
};
$err = fn($k) => isset($errs[$k]) ? '<span class="err">' . e($errs[$k]) . '</span>' : '';
$hasErr = fn($k) => isset($errs[$k]) ? 'has-error' : '';
?>
<div class="page-head">
  <div>
    <h1><?= $isNew ? 'Add offer' : 'Edit offer' ?></h1>
    <div class="sub"><?= $isNew ? 'Create a new catalogue offer as a draft change.' : 'Editing ' . e($o['offer_id'] ?? '') ?></div>
  </div>
  <div class="page-head__actions"><a class="btn btn--ghost" href="<?= e(url('/offers')) ?>">Cancel</a></div>
</div>

<div class="grid two">
  <div class="card">
    <form method="post" action="<?= e(url('/offers/save')) ?>" data-once>
      <?= App\Core\Csrf::field() ?>
      <input type="hidden" name="is_new" value="<?= $isNew ? '1' : '0' ?>">
      <?php if (!$isNew): ?><input type="hidden" name="row_version" value="<?= (int) ($o['row_version'] ?? 0) ?>"><?php endif; ?>
      <div class="form-grid">
        <?php if ($isNew): ?>
          <div class="field full">
            <label>Offer ID</label>
            <input type="text" value="Generated automatically from the category" disabled>
            <span class="hint">You don't set this — it is created for you, e.g. data_14.</span>
          </div>
        <?php else: ?>
          <div class="field full">
            <label>Offer ID</label>
            <input type="text" value="<?= e($o['offer_id'] ?? '') ?>" readonly>
            <input type="hidden" name="offer_id" value="<?= e($o['offer_id'] ?? '') ?>">
            <span class="hint">The app matches purchases by this ID, so it never changes.</span>
          </div>
        <?php endif; ?>
        <div class="field <?= $hasErr('category') ?>">
          <label>Category</label>
          <select name="category"><?php foreach (OfferRepository::CATEGORIES as $c): ?>
            <option value="<?= $c ?>" <?= $val('category', 'DATA') === $c ? 'selected' : '' ?>><?= $c ?></option>
          <?php endforeach; ?></select><?= $err('category') ?>
        </div>
        <div class="field <?= $hasErr('name') ?>">
          <label>Name / allowance</label>
          <input type="text" name="name" value="<?= e($val('name')) ?>" placeholder="2GB" required><?= $err('name') ?>
        </div>
        <div class="field <?= $hasErr('price') ?>">
          <label>Price (KSh)</label>
          <input type="number" name="price" value="<?= e($val('price')) ?>" min="1" required><?= $err('price') ?>
        </div>
        <div class="field <?= $hasErr('margin_bps') ?>">
          <label>Your margin <span class="muted small">(basis points, optional)</span></label>
          <input type="number" name="margin_bps" value="<?= e($val('margin_bps')) ?>" min="0" max="10000" placeholder="e.g. 400">
          <span class="hint">100 = 1%. What you actually make on this offer. Referral commission can never be saved above it.</span>
          <?= $err('margin_bps') ?>
        </div>
        <div class="field <?= $hasErr('commission_bps') ?>">
          <label>Referral commission <span class="muted small">(basis points, optional)</span></label>
          <input type="number" name="commission_bps" value="<?= e($val('commission_bps')) ?>" min="0" max="10000" placeholder="blank = use default">
          <span class="hint">Leave blank to use the programme default. Paid to whoever referred the buyer.</span>
          <?= $err('commission_bps') ?>
        </div>
        <div class="field <?= $hasErr('validity') ?>">
          <label>Validity</label>
          <input type="text" name="validity" value="<?= e($val('validity')) ?>" placeholder="24 Hrs" required><?= $err('validity') ?>
        </div>
        <div class="field">
          <label>Validity band</label>
          <select name="band"><?php foreach (OfferRepository::BANDS as $b): ?>
            <option value="<?= $b ?>" <?= $val('band', 'Daily') === $b ? 'selected' : '' ?>><?= $b ?></option>
          <?php endforeach; ?></select>
        </div>
        <div class="field <?= $hasErr('daily_rule') ?>">
          <label>Daily rule</label>
          <select name="daily_rule"><?php foreach (OfferRepository::RULES as $k => $lab): ?>
            <option value="<?= $k ?>" <?= $val('daily_rule', 'MULTIPLE_PER_DAY') === $k ? 'selected' : '' ?>><?= e($lab) ?></option>
          <?php endforeach; ?></select>
          <span class="hint">Purchase-awareness policy (Africa/Nairobi day).</span><?= $err('daily_rule') ?>
        </div>
        <div class="field <?= $hasErr('max_per_day') ?>">
          <label>Max per day <span class="muted small">(only for “Max per recipient”)</span></label>
          <input type="number" name="max_per_day" value="<?= e($val('max_per_day')) ?>" min="1" placeholder="e.g. 3"><?= $err('max_per_day') ?>
        </div>
        <div class="field <?= $hasErr('available_from') ?>">
          <label>Sells from <span class="muted small">(time of day, optional)</span></label>
          <input type="time" name="available_from" value="<?= e($toClock($val('available_from'))) ?>">
          <span class="hint">Nairobi time. Leave both blank if the offer sells all day.</span><?= $err('available_from') ?>
        </div>
        <div class="field <?= $hasErr('available_to') ?>">
          <label>Sells until <span class="muted small">(time of day, optional)</span></label>
          <input type="time" name="available_to" value="<?= e($toClock($val('available_to'))) ?>">
          <span class="hint">A window may cross midnight (e.g. 22:00 → 02:00).</span><?= $err('available_to') ?>
        </div>
        <div class="field">
          <label>Commercial tag <span class="muted small">(optional)</span></label>
          <input type="text" name="commercial_tag" value="<?= e($val('commercial_tag')) ?>" placeholder="Best value" maxlength="40">
        </div>
        <div class="field">
          <label>Sort hint</label>
          <input type="number" name="sort_hint" value="<?= e($val('sort_hint', 0)) ?>">
        </div>
        <div class="field">
          <label>Campaign starts <span class="muted small">(date, optional)</span></label>
          <input type="datetime-local" name="starts_at" value="<?= e($toLocal($val('starts_at', '') ?: null)) ?>">
        </div>
        <div class="field">
          <label>Campaign ends <span class="muted small">(date, optional)</span></label>
          <input type="datetime-local" name="ends_at" value="<?= e($toLocal($val('ends_at', '') ?: null)) ?>">
        </div>
        <div class="field full">
          <label>Customer-visible restrictions <span class="muted small">(optional)</span></label>
          <input type="text" name="restrictions" value="<?= e($val('restrictions')) ?>" maxlength="255" placeholder="e.g. New numbers only">
        </div>
      </div>
      <div class="row mt" style="justify-content:space-between">
        <label class="switch">
          <input type="checkbox" name="active" <?= ($val('status', 'active') === 'active' || $isNew) ? 'checked' : '' ?>>
          <span class="track"></span> <span>Active (visible in the app after publish)</span>
        </label>
        <label class="checkbox">
          <input type="checkbox" name="offline_eligible" <?= ((int) $val('offline_eligible', 1) === 1) ? 'checked' : '' ?>> Offline-purchase eligible
        </label>
      </div>
      <div class="mt"><button class="btn" type="submit"><?= icon('check', 18) ?> <?= $isNew ? 'Create offer' : 'Save changes' ?></button></div>
    </form>
  </div>

  <div class="card">
    <div class="card__head"><h3>Offer preview</h3></div>
    <div class="phone">
      <div style="background:var(--surface);border:1px solid var(--divider);border-radius:16px;padding:14px">
        <div class="row between"><span class="tag data" id="pv-cat"><?= e($val('category', 'DATA')) ?></span><span class="tag minutes" id="pv-tag"><?= e($val('commercial_tag')) ?: '' ?></span></div>
        <div class="stat__value" style="font-size:24px;margin-top:8px" id="pv-name"><?= e($val('name')) ?: 'Allowance' ?></div>
        <div class="muted" id="pv-validity"><?= e($val('validity')) ?: 'Validity' ?></div>
        <?php $pvFrom = $toClock($val('available_from')); $pvTo = $toClock($val('available_to')); ?>
        <?php if ($pvFrom !== '' && $pvTo !== ''): ?>
          <div class="muted small">Sold <?= e($pvFrom) ?> – <?= e($pvTo) ?> only</div>
        <?php endif; ?>
        <div class="between mt"><b id="pv-price"><?= e(ksh($val('price', 0))) ?></b><span class="btn btn--sm">Buy</span></div>
      </div>
    </div>
    <p class="phone__note">Approximate app card. Publish to update the live app.</p>
  </div>
</div>
