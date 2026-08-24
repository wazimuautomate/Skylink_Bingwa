<?php
use App\Services\BillboardService;

$b = $b ?? [];
$kind = $b['kind'] ?? 'simple';
$isAdvanced = $kind === 'advanced';
$targetAction = trim((string) ($b['target_action'] ?? ''));
if ($targetAction === '') {
    // A billboard created before tap targets existed still opens its linked offer.
    $targetAction = trim((string) ($b['linked_offer_id'] ?? '')) !== '' ? 'offer' : 'none';
}
$targetAction = in_array($targetAction, BillboardService::TARGET_ACTIONS, true) ? $targetAction : 'none';
$enabled = !isset($b['enabled']) || (int) $b['enabled'] === 1;
$image = $image ?? null;
$thumb = $thumb ?? null;
$animated = !empty($image) && (int) ($image['is_animated'] ?? 0) === 1;
$thumbFile = (string) ($thumb['stored_name'] ?? ($image['thumb_name'] ?? ''));
$state = $b ? BillboardService::effectiveState($b, nairobi_now()) : 'draft';

$toLocal = function (?string $utc) {
    if (!$utc) return '';
    try { return (new DateTimeImmutable($utc, new DateTimeZone('UTC')))->setTimezone(new DateTimeZone('Africa/Nairobi'))->format('Y-m-d\TH:i'); }
    catch (Throwable $e) { return ''; }
};

$targetChoices = [
    'none'     => 'Nothing — the advert is just a message',
    'offer'    => 'Open an offer',
    'category' => 'Open a category',
    'url'      => 'Open a web page',
    'internal' => 'Open a screen in the app',
];
?>
<style>
  .bb-target { border: 1px solid var(--divider); border-radius: var(--radius-sm); padding: 14px; }
  .bb-target .field + .field { margin-top: 12px; }
  .bb-only { font-size: 12px; color: var(--text-2); }
  .bb-preview-img { max-width: 100%; height: auto; border-radius: 8px; border: 1px solid var(--divider); display: block; }
  .bb-preview-wrap { display: flex; gap: 14px; align-items: flex-start; flex-wrap: wrap; }
  .bb-preview-meta { font-size: 12.5px; color: var(--text-2); line-height: 1.7; }
  .bb-thumb-still { width: 96px; height: auto; border-radius: 6px; border: 1px solid var(--divider); display: block; }
</style>

<div class="page-head">
  <div>
    <h1><?= $isNew ? 'New billboard' : 'Edit billboard' ?></h1>
    <div class="sub">Simple billboards generate copy from an offer with the tokens: <?= e(implode(', ', array_map(fn($t) => '{{' . $t . '}}', $tokens))) ?>.</div>
  </div>
  <div class="page-head__actions">
    <?php if (!$isNew): ?><span class="status <?= e(BillboardService::stateClass($state)) ?>"><?= e(BillboardService::stateLabel($state)) ?></span><?php endif; ?>
    <a class="btn btn--ghost" href="<?= e(url('/billboards')) ?>">Cancel</a>
  </div>
</div>

<form method="post" action="<?= e(url('/billboards/save')) ?>" enctype="multipart/form-data" data-once>
  <?= App\Core\Csrf::field() ?>
  <input type="hidden" name="is_new" value="<?= $isNew ? '1' : '0' ?>">
  <?php if (!$isNew): ?><input type="hidden" name="id" value="<?= (int) $b['id'] ?>"><?php endif; ?>

  <div class="grid two">
    <div class="stack">

      <div class="card">
        <div class="card__head"><h3>The advert</h3></div>
        <div class="form-grid">
          <div class="field"><label>Internal name</label><input type="text" name="name" value="<?= e($b['name'] ?? '') ?>" maxlength="120" required><span class="hint">Only you see this.</span></div>
          <div class="field"><label>Kind</label>
            <select name="kind">
              <option value="simple" <?= $kind === 'simple' ? 'selected' : '' ?>>Simple (offer-linked, always on)</option>
              <option value="advanced" <?= $isAdvanced ? 'selected' : '' ?>>Advanced (own words, picture, schedule)</option>
            </select>
            <span class="hint">Save to switch the form between the two.</span>
          </div>
          <div class="field"><label>Linked offer</label>
            <select name="linked_offer_id"><option value="">None</option>
              <?php foreach ($offers as $o): ?><option value="<?= e($o['offer_id']) ?>" <?= ($b['linked_offer_id'] ?? '') === $o['offer_id'] ? 'selected' : '' ?>><?= e($o['category'] . ' · ' . $o['name'] . ' · KSh ' . $o['price']) ?></option><?php endforeach; ?>
            </select>
          </div>
          <div class="field"><label>Tag</label><input type="text" name="tag" value="<?= e($b['tag'] ?? '') ?>" maxlength="40" data-preview-src="#pv-tag" placeholder="BEST VALUE"></div>
          <div class="field full"><label>Title <span class="muted small">(blank = auto from the offer for simple adverts)</span></label><input type="text" name="headline" value="<?= e($b['headline'] ?? '') ?>" maxlength="120" data-preview-src="#pv-headline" placeholder="{{offer_name}} for KSh {{price}}"></div>
          <div class="field full"><label>Description</label><input type="text" name="body" value="<?= e($b['body'] ?? '') ?>" maxlength="255" data-preview-src="#pv-body" placeholder="Stay connected for {{validity}}."></div>
          <div class="field"><label>Button label</label><input type="text" name="cta_label" value="<?= e($b['cta_label'] ?? 'Buy now') ?>" maxlength="40"></div>
          <?php if ($isAdvanced): ?>
            <div class="field"><label>Button deep link <span class="muted small">(what the app already reads)</span></label><input type="text" name="cta_destination" value="<?= e($b['cta_destination'] ?? '') ?>" maxlength="120" placeholder="skylinkbingwa://offers/data_6"></div>
          <?php endif; ?>
        </div>
      </div>

      <div class="card">
        <div class="card__head"><h3>What happens when someone taps this advert</h3></div>
        <div class="bb-target">
          <div class="field">
            <label for="bb-target-action">Tapping the advert should</label>
            <select id="bb-target-action" name="target_action">
              <?php foreach ($targetChoices as $key => $label): ?>
                <option value="<?= e($key) ?>" <?= $targetAction === $key ? 'selected' : '' ?>><?= e($label) ?></option>
              <?php endforeach; ?>
            </select>
            <span class="hint">Fill in only the box below that matches your choice — the rest is ignored and cleared when you save.</span>
          </div>
          <div class="field">
            <label>Open a category</label>
            <select name="target_category">
              <option value="">Choose a category</option>
              <?php foreach ($categories as $c): ?>
                <option value="<?= e($c['category_key']) ?>" <?= ($b['target_category'] ?? '') === $c['category_key'] ? 'selected' : '' ?>><?= e($c['label'] !== '' ? $c['label'] : $c['category_key']) ?></option>
              <?php endforeach; ?>
            </select>
            <span class="bb-only">Only used when you choose “Open a category”.</span>
          </div>
          <div class="field">
            <label>Web page address</label>
            <input type="url" name="click_url" value="<?= e($b['click_url'] ?? '') ?>" maxlength="255" placeholder="https://example.co.ke/promo">
            <span class="bb-only">Only used when you choose “Open a web page”. Must start with https:// — plain http:// is refused.</span>
          </div>
          <div class="field">
            <label>App screen</label>
            <input type="text" name="internal_action" value="<?= e($b['internal_action'] ?? '') ?>" maxlength="60" placeholder="favourites">
            <span class="bb-only">Only used when you choose “Open a screen in the app”. Ask the developer for the exact screen name.</span>
          </div>
          <p class="bb-only" style="margin:12px 0 0">“Open an offer” uses the linked offer chosen above.</p>
        </div>
      </div>

      <?php if ($isAdvanced): ?>
        <div class="card">
          <div class="card__head"><h3>Picture or animation</h3></div>
          <?php if (!empty($image)): ?>
            <div class="bb-preview-wrap">
              <div>
                <img class="bb-preview-img" src="<?= e(url('/uploads/' . $image['stored_name'])) ?>" alt="<?= e($b['alt_text'] ?? 'Current advert image') ?>" style="max-width:320px">
                <?php if ($animated): ?><p class="bb-only" style="margin-top:6px"><span class="tag minutes">Animated</span> <?= (int) ($image['frame_count'] ?? 0) ?> frames — stored exactly as uploaded.</p><?php endif; ?>
              </div>
              <div>
                <div class="bb-preview-meta">
                  <?= (int) $image['width'] ?>×<?= (int) $image['height'] ?> px<br>
                  <?= e(strtoupper(str_replace('image/', '', (string) $image['mime']))) ?> · <?= e(App\Services\ImageUploader::humanBytes((int) $image['bytes'])) ?><br>
                  <?php if ((string) ($image['original_name'] ?? '') !== ''): ?>Uploaded as <span class="mono"><?= e($image['original_name']) ?></span><?php endif; ?>
                </div>
                <?php if ($thumbFile !== ''): ?>
                  <p class="bb-only" style="margin:10px 0 4px">Still thumbnail</p>
                  <img class="bb-thumb-still" src="<?= e(url('/uploads/' . $thumbFile)) ?>" alt="Still thumbnail">
                <?php else: ?>
                  <p class="bb-only" style="margin-top:10px">No thumbnail was generated on this server.</p>
                <?php endif; ?>
              </div>
            </div>
          <?php else: ?>
            <div class="empty" style="padding:22px"><?= icon('image', 28) ?><p>No picture yet.</p></div>
          <?php endif; ?>
          <div class="form-grid" style="margin-top:14px">
            <div class="field full">
              <label>Upload a picture or animation</label>
              <input type="file" name="image" accept="image/png,image/jpeg,image/webp,image/gif">
              <span class="hint">PNG, JPEG or WebP up to <?= e($maxImageMb) ?>; GIF up to <?= e($maxGifMb) ?>. Still pictures are re-saved to strip hidden data; animated GIFs are kept exactly as uploaded and get a still thumbnail. SVG is not allowed.</span>
            </div>
            <div class="field full"><label>Describe the picture (alt text)</label><input type="text" name="alt_text" value="<?= e($b['alt_text'] ?? '') ?>" maxlength="160" placeholder="Poster showing the 2GB weekly bundle"></div>
            <?php if (!empty($image)): ?>
              <div class="field full"><label class="checkbox"><input type="checkbox" name="remove_image" value="1"> Remove the current picture when I save</label></div>
            <?php endif; ?>
          </div>
        </div>
      <?php endif; ?>

      <div class="card">
        <div class="card__head"><h3>Where and when it shows</h3></div>
        <div class="form-grid">
          <div class="field"><label>Order</label><input type="number" name="display_order" value="<?= (int) ($b['display_order'] ?? 0) ?>" min="0" max="9999"><span class="hint">The lowest number shows first.</span></div>
          <div class="field"><label>Priority <span class="muted small">(tie-break)</span></label><input type="number" name="priority" value="<?= (int) ($b['priority'] ?? 5) ?>" min="0" max="9999"><span class="hint">Only used when two adverts share the same order number.</span></div>
          <div class="field"><label>Times per day <span class="muted small">(0 = no limit)</span></label><input type="number" name="frequency_cap" value="<?= (int) ($b['frequency_cap'] ?? 0) ?>" min="0"></div>
          <div class="field"><label>Status</label>
            <select name="status"><?php foreach (['draft', 'scheduled', 'active', 'paused', 'archived'] as $s): ?><option value="<?= e($s) ?>" <?= ($b['status'] ?? 'draft') === $s ? 'selected' : '' ?>><?= e(ucfirst($s)) ?></option><?php endforeach; ?></select>
            <span class="hint">Only <b>active</b> and <b>scheduled</b> adverts are ever published.</span>
          </div>
          <?php if ($isAdvanced): ?>
            <div class="field"><label>Starts at <span class="muted small">(Nairobi)</span></label><input type="datetime-local" name="starts_at" value="<?= e($toLocal($b['starts_at'] ?? null)) ?>"></div>
            <div class="field"><label>Ends at <span class="muted small">(Nairobi)</span></label><input type="datetime-local" name="ends_at" value="<?= e($toLocal($b['ends_at'] ?? null)) ?>"></div>
            <div class="field full">
              <div class="alert info"><?= icon('clock', 18) ?><div>Leave both blank to show the advert whenever it is active. With a start date it goes live by itself at that moment, and it stops by itself at the end date — you do not have to come back and switch anything.</div></div>
            </div>
          <?php else: ?>
            <div class="field full">
              <div class="alert info"><?= icon('info', 18) ?><div>Simple adverts are <b>always on</b>: they ignore start and end dates on purpose, so an offer-linked advert can never quietly expire. Switch to Advanced if you need a schedule.</div></div>
            </div>
          <?php endif; ?>
          <div class="field full">
            <input type="hidden" name="enabled" value="0">
            <label class="switch"><input type="checkbox" name="enabled" value="1" <?= $enabled ? 'checked' : '' ?>><span class="track"></span><span>Show this advert in the app</span></label>
            <span class="hint">An off switch that works on its own: an advert that is off is never published, whatever its status or dates say.</span>
          </div>
        </div>
      </div>

      <div class="between">
        <button class="btn" type="submit"><?= icon('check', 18) ?> Save billboard</button>
        <span class="muted small">Changes reach customers at the next publish.</span>
      </div>
    </div>

    <div class="stack">
      <div class="card">
        <div class="card__head"><h3>Preview (light)</h3></div>
        <div class="phone">
          <div style="background:var(--surface);border:1px solid var(--divider);border-radius:16px;padding:14px">
            <?php if (!empty($image)): ?>
              <img class="bb-preview-img" style="margin-bottom:10px" src="<?= e(url('/uploads/' . $image['stored_name'])) ?>" alt="<?= e($b['alt_text'] ?? '') ?>">
            <?php endif; ?>
            <span class="tag minutes" id="pv-tag" data-empty="BEST VALUE"><?= e($b['tag'] ?? '') ?: 'BEST VALUE' ?></span>
            <div class="stat__value" style="font-size:18px;margin-top:8px" id="pv-headline" data-empty="Title"><?= e($b['headline'] ?? '') ?: 'Title' ?></div>
            <div class="muted small" id="pv-body" data-empty="Description"><?= e($b['body'] ?? '') ?: 'Description' ?></div>
            <div class="mt"><span class="btn btn--sm"><?= e($b['cta_label'] ?? 'Buy now') ?></span></div>
          </div>
        </div>
        <p class="phone__note">Simple billboards resolve {{tokens}} from the linked offer at publish. An animated GIF plays in the app exactly as it plays here.</p>
      </div>

      <div class="card">
        <div class="card__head"><h3>Good to know</h3></div>
        <ul class="small muted" style="margin:0;padding-left:18px;line-height:1.9">
          <li>Order decides the carousel position: <b>lowest number first</b>.</li>
          <li>The state you see in the list is worked out from the dates in Nairobi time, not from a stored flag.</li>
          <li>A simple advert whose offer stops being active is dropped at publish instead of showing a broken price.</li>
          <li>Uploaded files are renamed to a random name and stored where they cannot run as code.</li>
        </ul>
      </div>
    </div>
  </div>
</form>
