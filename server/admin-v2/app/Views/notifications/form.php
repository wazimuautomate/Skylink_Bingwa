<?php
/**
 * Notification rule editor.
 *
 * One form holds everything: what kind of message it is, the moment the app is allowed
 * to show it, the days/times window, and the wordings the app picks from at random.
 * "Preview" posts the same form to /notifications/preview and comes straight back with
 * the sample values filled in — unsaved work is never thrown away.
 */
$old = \App\Core\Session::get('_old', []);
\App\Core\Session::forget('_old'); // consume repopulation data exactly once
$errs = $old['_errors'] ?? [];
$c = $c ?? [];
$preview = $preview ?? [];

$val = function (string $key, $default = '') use ($old, $c) {
    if (array_key_exists($key, $old)) {
        return $old[$key];
    }
    return (isset($c[$key]) && $c[$key] !== null) ? $c[$key] : $default;
};
$err = function (string $key) use ($errs): string {
    return isset($errs[$key]) ? '<span class="err">' . e($errs[$key]) . '</span>' : '';
};
$toLocal = function ($utc) {
    if (!$utc) {
        return '';
    }
    try {
        return (new DateTimeImmutable((string) $utc, new DateTimeZone('UTC')))
            ->setTimezone(new DateTimeZone('Africa/Nairobi'))->format('Y-m-d\TH:i');
    } catch (Throwable $ex) {
        return '';
    }
};

/* Wordings: repopulated submission first, then the saved rows, then blank spares. */
$rows = [];
if (isset($old['variation_title']) && is_array($old['variation_title'])) {
    foreach ($old['variation_title'] as $i => $title) {
        $rows[] = ['title' => (string) $title, 'body' => (string) ($old['variation_body'][$i] ?? '')];
    }
} else {
    foreach ($variations as $vr) {
        $rows[] = ['title' => (string) ($vr['title'] ?? ''), 'body' => (string) ($vr['body'] ?? '')];
    }
}
// Without JavaScript the operator still needs empty rows to type into.
$blanks = $rows === [] ? 3 : 2;
for ($i = 0; $i < $blanks; $i++) {
    $rows[] = ['title' => '', 'body' => ''];
}

$selectedDays = (isset($old['days']) && is_array($old['days']))
    ? array_map('intval', $old['days'])
    : \App\Services\NotificationService::dayList($c['days_of_week'] ?? '');

$currentTrigger = (string) $val('trigger_type', 'manual');
$isActive = ((string) $val('status', 'draft')) === 'active';
$isEnabled = (int) $val('enabled', 1) === 1;

/* Schedule problems are reported per message, not per field. */
$scheduleErrs = [];
foreach ($errs as $errKey => $errMsg) {
    if (strpos((string) $errKey, 'schedule_') === 0) {
        $scheduleErrs[] = (string) $errMsg;
    }
}
?>
<style>
  .nform .var-row { border: 1px solid var(--divider); border-radius: var(--radius-sm); padding: 12px; margin-bottom: 10px; }
  .nform .var-row + .var-row { margin-top: 0; }
  .nform .var-row .field { margin-top: 8px; }
  .nform .token-chips { display: flex; flex-wrap: wrap; gap: 8px; }
  .nform button.chip { cursor: pointer; border: 1px solid var(--divider); background: var(--grouped); font-family: inherit; }
  .nform button.chip:hover { background: var(--surface); }
  .nform .days { display: flex; flex-wrap: wrap; gap: 12px; }
  .nform .days label { font-weight: 500; }
  .nform .is-hidden { display: none; }
  .nform .pv-item { border-top: 1px dashed var(--divider); padding: 10px 0; }
  .nform .pv-item:first-child { border-top: 0; padding-top: 0; }
  .nform .var-table th, .nform .var-table td { padding: 6px 8px; border-bottom: 1px solid var(--divider); text-align: left; vertical-align: top; }
</style>

<div class="page-head">
  <div>
    <h1><?= $isNew ? 'New notification' : 'Edit notification' ?></h1>
    <div class="sub">All days and times are Africa/Nairobi. The phone decides whether to actually show anything.</div>
  </div>
  <div class="page-head__actions"><a class="btn btn--ghost" href="<?= e(url('/notifications')) ?>">Back</a></div>
</div>

<form class="nform" method="post" action="<?= e(url('/notifications/save')) ?>" data-once>
  <?= App\Core\Csrf::field() ?>
  <input type="hidden" name="is_new" value="<?= $isNew ? '1' : '0' ?>">
  <input type="hidden" name="id" value="<?= (int) ($c['id'] ?? 0) ?>">
  <input type="hidden" name="row_version" value="<?= (int) ($c['row_version'] ?? 0) ?>">

  <div class="grid two">
    <div class="stack">

      <!-- ---------------------------------------------------------- basics -->
      <div class="card">
        <div class="card__head"><?= icon('notifications', 18) ?><h3>What is this notification?</h3></div>
        <div class="form-grid">
          <div class="field full <?= isset($errs['name']) ? 'has-error' : '' ?>">
            <label for="nf-name">Name it for yourself</label>
            <input id="nf-name" type="text" name="name" maxlength="120" required value="<?= e($val('name')) ?>" placeholder="Evening reminder to top up">
            <span class="hint">Only you see this name. Customers never do.</span>
            <?= $err('name') ?>
          </div>
          <div class="field <?= isset($errs['category']) ? 'has-error' : '' ?>">
            <label for="nf-category">What kind of message is it?</label>
            <select id="nf-category" name="category" required>
              <option value="">Choose…</option>
              <?php foreach ($categories as $key => $cat): ?>
                <option value="<?= e($key) ?>" <?= ((string) $val('category')) === (string) $key ? 'selected' : '' ?>><?= e($cat['label']) ?></option>
              <?php endforeach; ?>
            </select>
            <span class="hint">The app groups messages by kind and lets customers switch promotions off.</span>
            <?= $err('category') ?>
          </div>
          <div class="field">
            <label for="nf-notes">Private note <span class="muted small">(optional)</span></label>
            <input id="nf-notes" type="text" name="notes" maxlength="255" value="<?= e($val('notes')) ?>" placeholder="Why this exists, who asked for it">
          </div>
        </div>
      </div>

      <!-- ------------------------------------------------- who sees it when -->
      <div class="card">
        <div class="card__head"><?= icon('clock', 18) ?><h3>Who sees this and when</h3></div>

        <div class="form-grid">
          <div class="field <?= isset($errs['trigger_type']) ? 'has-error' : '' ?>">
            <label for="nf-trigger">The moment the app checks this</label>
            <select id="nf-trigger" name="trigger_type">
              <?php foreach ($triggers as $key => $trig): ?>
                <option value="<?= e($key) ?>" data-desc="<?= e($trig['description']) ?>" <?= $currentTrigger === (string) $key ? 'selected' : '' ?>><?= e($trig['label']) ?></option>
              <?php endforeach; ?>
            </select>
            <span class="hint" id="nf-trigger-hint"><?= e($triggers[$currentTrigger]['description'] ?? 'Choose the moment this message becomes relevant.') ?></span>
            <?= $err('trigger_type') ?>
          </div>
        </div>

        <div class="mt">
          <div class="alert info"><?= icon('info', 18) ?><span>Leave the dates, days and times empty to allow this notification at any time. Anything you set here only narrows when the phone may show it.</span></div>
        </div>

        <?php if ($scheduleErrs !== []): ?>
          <div class="alert warning mt"><?= icon('warning', 18) ?><span>
            <?php foreach ($scheduleErrs as $msg): ?><?= e($msg) ?><br><?php endforeach; ?>
          </span></div>
        <?php endif; ?>

        <div class="form-grid mt">
          <div class="field">
            <label for="nf-starts">Show from <span class="muted small">(optional)</span></label>
            <input id="nf-starts" type="date" name="starts_on" value="<?= e(substr((string) $val('starts_on'), 0, 10)) ?>">
          </div>
          <div class="field">
            <label for="nf-ends">Stop after <span class="muted small">(optional)</span></label>
            <input id="nf-ends" type="date" name="ends_on" value="<?= e(substr((string) $val('ends_on'), 0, 10)) ?>">
          </div>
          <div class="field full">
            <label>Days of the week</label>
            <div class="days">
              <?php foreach ($dayNames as $num => $label): ?>
                <label class="checkbox"><input type="checkbox" name="days[]" value="<?= (int) $num ?>" <?= in_array((int) $num, $selectedDays, true) ? 'checked' : '' ?>> <?= e($label) ?></label>
              <?php endforeach; ?>
            </div>
            <span class="hint">Tick nothing for every day.</span>
          </div>
          <div class="field">
            <label for="nf-tstart">Only between <span class="muted small">(optional)</span></label>
            <input id="nf-tstart" type="time" name="allowed_time_start" value="<?= e($val('allowed_time_start')) ?>">
          </div>
          <div class="field">
            <label for="nf-tend">and</label>
            <input id="nf-tend" type="time" name="allowed_time_end" value="<?= e($val('allowed_time_end')) ?>">
            <span class="hint">Set both or neither. An end earlier than the start means the window crosses midnight.</span>
          </div>
          <div class="field">
            <label for="nf-cooldown">Rest between two showings</label>
            <input id="nf-cooldown" type="number" name="cooldown_minutes" min="0" max="10080" step="1" value="<?= (int) $val('cooldown_minutes', 0) ?>">
            <span class="hint">Minutes. 0 means no extra rest. Maximum 10080 (7 days).</span>
          </div>
          <div class="field">
            <label for="nf-cap">Most times per day</label>
            <input id="nf-cap" type="number" name="frequency_cap" min="0" step="1" value="<?= (int) $val('frequency_cap', 1) ?>">
            <span class="hint">0 means the app applies its own shared cap only.</span>
          </div>
        </div>
      </div>

      <!-- --------------------------------------------------------- wordings -->
      <div class="card">
        <div class="card__head"><?= icon('variations', 18) ?><h3>What it says</h3></div>
        <div class="alert info"><?= icon('info', 18) ?><span>Write a few different wordings. The app picks one at random each time, so the same message never feels repetitive. At least one wording is needed before this can go live.</span></div>

        <?php if ($variables !== []): ?>
          <div class="mt">
            <div class="small muted mb">Tap to insert into the box you were last typing in:</div>
            <div class="token-chips">
              <?php foreach ($variables as $vkey => $var): ?>
                <button type="button" class="chip" data-token="{{<?= e($vkey) ?>}}" title="<?= e($var['description']) ?>"><?= e($var['label']) ?> <span class="mono muted">{{<?= e($vkey) ?>}}</span></button>
              <?php endforeach; ?>
            </div>
          </div>
        <?php endif; ?>

        <?php if (isset($errs['variations'])): ?>
          <div class="alert warning mt"><?= icon('warning', 18) ?><span><?= e($errs['variations']) ?></span></div>
        <?php endif; ?>

        <div class="mt" id="var-rows">
          <?php foreach ($rows as $i => $vr): ?>
            <div class="var-row">
              <div class="between">
                <b class="small">Wording <span class="var-n"><?= $i + 1 ?></span></b>
                <button type="button" class="btn btn--ghost btn--sm" data-var-remove><?= icon('trash', 14) ?> Remove</button>
              </div>
              <div class="field">
                <label>Title</label>
                <input type="text" name="variation_title[]" maxlength="120" data-token-target <?= $i === 0 ? 'data-preview-src="#pv-title"' : '' ?> value="<?= e($vr['title']) ?>" placeholder="Good evening, {{first_name}}">
              </div>
              <div class="field">
                <label>Message</label>
                <textarea name="variation_body[]" maxlength="255" rows="2" data-token-target <?= $i === 0 ? 'data-preview-src="#pv-body"' : '' ?> placeholder="{{recommended_offer}} is ready when you are."><?= e($vr['body']) ?></textarea>
              </div>
            </div>
          <?php endforeach; ?>
        </div>

        <template id="var-template">
          <div class="var-row">
            <div class="between">
              <b class="small">Wording <span class="var-n"></span></b>
              <button type="button" class="btn btn--ghost btn--sm" data-var-remove><?= icon('trash', 14) ?> Remove</button>
            </div>
            <div class="field">
              <label>Title</label>
              <input type="text" name="variation_title[]" maxlength="120" data-token-target value="" placeholder="Good evening, {{first_name}}">
            </div>
            <div class="field">
              <label>Message</label>
              <textarea name="variation_body[]" maxlength="255" rows="2" data-token-target placeholder="{{recommended_offer}} is ready when you are."></textarea>
            </div>
          </div>
        </template>

        <button type="button" class="btn btn--secondary btn--sm" id="var-add"><?= icon('plus', 16) ?> Add another wording</button>
      </div>

      <!-- --------------------------------------------------------- advanced -->
      <div class="card">
        <div class="card__head"><?= icon('settings', 18) ?><h3>Extra settings</h3></div>
        <div class="form-grid">
          <div class="field">
            <label for="nf-priority">How important is it?</label>
            <select id="nf-priority" name="priority">
              <?php foreach ($priorities as $key => $label): ?>
                <option value="<?= e($key) ?>" <?= ((string) $val('priority', 'normal')) === (string) $key ? 'selected' : '' ?>><?= e($label) ?></option>
              <?php endforeach; ?>
            </select>
          </div>
          <div class="field">
            <label for="nf-offer">Related offer <span class="muted small">(optional)</span></label>
            <select id="nf-offer" name="linked_offer_id">
              <option value="">None</option>
              <?php foreach ($offers as $o): ?>
                <option value="<?= e($o['offer_id']) ?>" <?= ((string) $val('linked_offer_id')) === (string) $o['offer_id'] ? 'selected' : '' ?>><?= e($o['category'] . ' · ' . $o['name'] . ' (' . $o['offer_id'] . ')') ?></option>
              <?php endforeach; ?>
            </select>
          </div>
          <div class="field">
            <label for="nf-deeplink">Where tapping it goes <span class="muted small">(optional)</span></label>
            <input id="nf-deeplink" type="text" name="deep_link" maxlength="120" value="<?= e($val('deep_link')) ?>" placeholder="skylinkbingwa://offers/data_6">
          </div>
          <div class="field">
            <label for="nf-expires">Hard stop <span class="muted small">(optional)</span></label>
            <input id="nf-expires" type="datetime-local" name="expires_at" value="<?= e($toLocal($val('expires_at', null))) ?>">
            <span class="hint">An exact moment after which the app ignores this completely.</span>
          </div>
        </div>
        <div class="row mt" style="gap:18px">
          <label class="checkbox"><input type="checkbox" name="respect_quiet_hours" <?= (int) $val('respect_quiet_hours', 1) === 1 ? 'checked' : '' ?>> Stay silent during quiet hours</label>
          <label class="checkbox"><input type="checkbox" name="suppress_recent_purchase" <?= (int) $val('suppress_recent_purchase', 1) === 1 ? 'checked' : '' ?>> Skip it just after a purchase</label>
        </div>
      </div>

      <!-- ------------------------------------------------------------ save -->
      <div class="card">
        <div class="row" style="gap:22px">
          <label class="switch"><input type="checkbox" name="enabled" <?= $isEnabled ? 'checked' : '' ?>><span class="track"></span><span>Switched on</span></label>
          <label class="switch"><input type="checkbox" name="active" <?= $isActive ? 'checked' : '' ?>><span class="track"></span><span>Ready to go live</span></label>
        </div>
        <p class="small muted mt">A switched-off notification is never shown. “Ready to go live” includes it in the next publish; leave it off to keep working on a draft.</p>
        <div class="row mt" style="gap:10px">
          <button class="btn" type="submit"><?= icon('check', 18) ?> Save</button>
          <button class="btn btn--secondary" type="submit" formaction="<?= e(url('/notifications/preview')) ?>" formnovalidate><?= icon('eye', 18) ?> Preview</button>
          <a class="btn btn--ghost" href="<?= e(url('/notifications')) ?>">Cancel</a>
        </div>
      </div>
    </div>

    <!-- ------------------------------------------------------ right column -->
    <div class="stack">
      <div class="card">
        <div class="card__head"><?= icon('phone', 18) ?><h3>On the phone</h3></div>
        <div class="phone">
          <div style="background:var(--surface);border:1px solid var(--divider);border-radius:14px;padding:12px;display:flex;gap:10px">
            <img class="brand__logo" src="<?= e(asset('img/logo.png')) ?>" alt="Skylink Bingwa" style="width:28px;height:28px;border-radius:8px">
            <div style="min-width:0">
              <b class="small" id="pv-title" data-empty="Notification title"><?= e(trim((string) ($rows[0]['title'] ?? '')) !== '' ? $rows[0]['title'] : 'Notification title') ?></b>
              <div class="small muted" id="pv-body" data-empty="Message preview"><?= e(trim((string) ($rows[0]['body'] ?? '')) !== '' ? $rows[0]['body'] : 'Message preview') ?></div>
              <div class="small muted mt">Skylink Bingwa · now</div>
            </div>
          </div>
        </div>
        <p class="phone__note">Numbers, receipts and balances never appear on the lock screen.</p>
      </div>

      <?php if ($preview !== []): ?>
        <div class="card">
          <div class="card__head"><?= icon('eye', 18) ?><h3>Preview</h3><span class="spacer"></span>
            <span class="status <?= !empty($preview['live_now']) ? 'active' : 'archived' ?>">Shown now? <?= !empty($preview['live_now']) ? 'Yes' : 'No' ?></span>
          </div>
          <p class="small muted"><?= e((string) $preview['schedule']) ?> · checked <?= e((string) $preview['checked_at']) ?></p>
          <?php if (!empty($preview['errors'])): ?>
            <div class="alert warning mt"><?= icon('warning', 18) ?><span>
              <?php foreach ($preview['errors'] as $msg): ?><?= e($msg) ?><br><?php endforeach; ?>
            </span></div>
          <?php endif; ?>
          <div class="mt">
            <?php if ($preview['variations'] === []): ?>
              <p class="small muted">No wording written yet.</p>
            <?php else: ?>
              <?php foreach ($preview['variations'] as $pv): ?>
                <div class="pv-item">
                  <div class="small muted">Wording <?= (int) $pv['number'] ?></div>
                  <?php if (trim((string) $pv['title']) !== ''): ?>
                    <b class="small"><?= e($pv['title']) ?></b>
                  <?php else: ?>
                    <b class="small muted">(no title)</b>
                  <?php endif; ?>
                  <div class="small"><?= e($pv['body']) ?></div>
                  <?php if ($pv['unsupported'] !== []): ?>
                    <div class="small" style="color:var(--error)">Unknown variable: <?= e(implode(', ', array_map(static fn($t) => '{{' . $t . '}}', $pv['unsupported']))) ?></div>
                  <?php endif; ?>
                </div>
              <?php endforeach; ?>
            <?php endif; ?>
          </div>
          <p class="small muted mt">Sample values only. On the phone these come from that device, and nothing is sent back here.</p>
        </div>
      <?php endif; ?>

      <?php if ($variables !== []): ?>
        <div class="card">
          <div class="card__head"><?= icon('info', 18) ?><h3>Variables you can use</h3></div>
          <div class="table-wrap">
            <table class="var-table" style="width:100%">
              <thead><tr><th>Token</th><th>Example</th></tr></thead>
              <tbody>
                <?php foreach ($variables as $vkey => $var): ?>
                  <tr>
                    <td><span class="mono">{{<?= e($vkey) ?>}}</span><br><span class="small muted"><?= e($var['description']) ?></span></td>
                    <td class="small"><?= e($var['sample_value']) ?></td>
                  </tr>
                <?php endforeach; ?>
              </tbody>
            </table>
          </div>
          <p class="small muted mt">Anything else in double braces is rejected when you save.</p>
        </div>
      <?php endif; ?>
    </div>
  </div>
</form>

<?php /* The wording rows, variable chips and trigger/event toggle live in
        assets/js/app.js: an inline <script> here is blocked by the Content-Security-Policy
        (script-src 'self'). */ ?>
