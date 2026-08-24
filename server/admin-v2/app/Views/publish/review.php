<?php
/**
 * Review & publish — the last stop before every Android device is affected.
 *
 * States the impact in plain terms: how many values changed, which resources move, and
 * what a device holding the current version will actually download. Publishing needs an
 * explicit tick; the server refuses the POST without it.
 */

require_once __DIR__ . '/../preview/_changes.php';

$s = $summary;
$hasErrors = !empty($errors);
$count = (int) $s['pendingCount'];
$live  = (int) $s['liveVersion'];
$draft = (int) $s['draftVersion'];
$moving = array_values(array_filter($resources, static fn($r) => $r['moves']));
$staying = array_values(array_filter($resources, static fn($r) => !$r['moves']));
$canPublish = can('publish.execute');
?>
<?= mb_change_group_styles() ?>
<style>
.rv-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 18px; }
.rv-grid .stat__value { font-size: 23px; }
.rv-list { list-style: none; margin: 8px 0 0; padding: 0; display: flex; flex-direction: column; gap: 6px; font-size: 13px; }
.rv-list li { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.rv-list .rv-move { font-weight: 600; }
.rv-confirm { display: flex; align-items: flex-start; gap: 10px; padding: 12px 14px; border: 1px solid var(--divider); border-radius: var(--radius-sm); font-size: 13px; }
.rv-confirm input { margin-top: 2px; width: 16px; height: 16px; flex: none; }
</style>

<div class="page-head">
  <div>
    <h1>Review &amp; publish</h1>
    <div class="sub">Publishing creates version v<?= $draft ?>. Devices download it on their next background sync.</div>
  </div>
  <div class="page-head__actions">
    <span class="chip"><span class="dot"></span>Live v<?= $live ?></span>
    <a class="btn btn--ghost btn--sm" href="<?= e(url('/preview')) ?>"><?= icon('eye', 16) ?> Back to preview</a>
  </div>
</div>

<?php if ($hasErrors): ?>
  <div class="alert error mb"><div><b>Publishing is blocked until these are fixed.</b><ul style="margin:6px 0 0 16px;text-align:left">
    <?php foreach ($errors as $er): ?><li><?= e($er) ?></li><?php endforeach; ?>
  </ul></div></div>
<?php endif; ?>
<?php if (!empty($warnings)): ?>
  <div class="alert warning mb"><div><b>Warnings — publishing is still allowed.</b><ul style="margin:6px 0 0 16px;text-align:left">
    <?php foreach ($warnings as $wn): ?><li><?= e($wn) ?></li><?php endforeach; ?>
  </ul></div></div>
<?php endif; ?>
<?php if (!$hasErrors && empty($warnings)): ?>
  <div class="alert success mb"><?= icon('check', 18) ?><div>Validation passed. No errors and no warnings.</div></div>
<?php endif; ?>

<div class="grid two">
  <div class="stack">
    <div class="card">
      <div class="card__head"><?= icon('layers', 18) ?><h2>What you are publishing</h2></div>
      <div class="rv-grid">
        <div class="stat"><span class="stat__label">Changes</span><span class="stat__value"><?= $count ?></span></div>
        <div class="stat"><span class="stat__label">Modules affected</span><span class="stat__value"><?= count($groups) ?></span></div>
        <div class="stat"><span class="stat__label">Resources moving</span><span class="stat__value"><?= count($moving) ?></span></div>
        <div class="stat"><span class="stat__label">New version</span><span class="stat__value">v<?= $draft ?></span></div>
      </div>
      <?php if ($count === 0): ?>
        <div class="alert info mt"><?= icon('info', 18) ?><div>There is nothing to publish. The live configuration already matches the draft.</div></div>
      <?php endif; ?>
    </div>

    <div class="card">
      <div class="card__head"><?= icon('sync', 18) ?><h3>Sync impact</h3></div>
      <p class="small muted">
        A device holding v<?= $live ?> compares each resource version and downloads only the ones that moved.
        Everything listed as unchanged keeps its number and costs the device nothing.
      </p>
      <?php if ($moving): ?>
        <p class="small mt"><b>Will be downloaded again</b></p>
        <ul class="rv-list">
          <?php foreach ($moving as $r): ?>
            <li>
              <span class="rv-move"><?= e($r['label']) ?></span>
              <span class="muted">v<?= (int) $r['from'] ?> &rarr;</span>
              <b>v<?= (int) $r['to'] ?></b>
              <span class="tag muted"><?= (int) $r['count'] ?> change<?= (int) $r['count'] === 1 ? '' : 's' ?></span>
            </li>
          <?php endforeach; ?>
        </ul>
      <?php endif; ?>
      <?php if ($staying): ?>
        <p class="small mt"><b>Unchanged — not downloaded</b></p>
        <div class="res-strip">
          <?php foreach ($staying as $r): ?>
            <span class="res-chip"><span><?= e($r['label']) ?></span><b>v<?= (int) $r['from'] ?></b></span>
          <?php endforeach; ?>
        </div>
      <?php endif; ?>
    </div>

    <div class="card">
      <div class="card__head"><?= icon('publish', 18) ?><h3>Changes included</h3><span class="spacer"></span>
        <span class="tag muted"><?= $count ?></span>
      </div>
      <?php if ($groups): ?>
        <?= mb_change_groups($groups) ?>
      <?php else: ?>
        <div class="empty"><?= icon('check', 32) ?><h3>Nothing to publish</h3><p>The live configuration matches the draft.</p></div>
      <?php endif; ?>
    </div>
  </div>

  <div class="stack">
    <div class="card">
      <div class="card__head"><?= icon('shield', 18) ?><h3>Publish v<?= $draft ?></h3></div>
      <?php if ($canPublish): ?>
        <form method="post" action="<?= e(url('/publish/execute')) ?>" data-once>
          <?= App\Core\Csrf::field() ?>
          <div class="field mb">
            <label for="notes">Release notes <span class="muted small">(optional)</span></label>
            <textarea id="notes" name="notes" rows="4" placeholder="What changed and why. Stored with the release."></textarea>
            <span class="hint">Saved on the release so anyone can see later why v<?= $draft ?> went out.</span>
          </div>
          <label class="rv-confirm mb">
            <input type="checkbox" name="confirm" value="yes" required <?= $hasErrors || $count === 0 ? 'disabled' : '' ?>>
            <span>I have reviewed these <?= $count ?> change<?= $count === 1 ? '' : 's' ?> and want them sent to every Skylink Bingwa app.</span>
          </label>
          <button class="btn btn--block" type="submit" <?= $hasErrors || $count === 0 ? 'disabled' : '' ?>>
            <?= icon('publish', 18) ?> Publish v<?= $draft ?>
          </button>
          <?php if ($count === 0): ?>
            <p class="small muted mt">There are no changes to publish.</p>
          <?php elseif ($hasErrors): ?>
            <p class="small muted mt">Fix the errors above, then publish.</p>
          <?php endif; ?>
        </form>
      <?php else: ?>
        <div class="alert info"><?= icon('info', 18) ?><div>You can review changes but do not have permission to publish. Ask the Super Admin.</div></div>
      <?php endif; ?>
      <div class="mt small muted">
        <p>Each publish is immutable: it gets a version number, a SHA-256 checksum and a signature. Old versions are never edited.</p>
      </div>
      <div class="mt"><a class="btn btn--ghost btn--sm btn--block" href="<?= e(url('/releases')) ?>"><?= icon('versions', 16) ?> Release history</a></div>
    </div>

    <div class="card">
      <div class="card__head"><?= icon('clock', 18) ?><h3>Current live release</h3></div>
      <div class="stack" style="gap:8px">
        <div class="between"><span class="muted small">Version</span><b>v<?= $live ?></b></div>
        <div class="between"><span class="muted small">Published</span><span class="small"><?= e(fmt_nairobi($s['lastPublishedAt'])) ?></span></div>
        <div class="between"><span class="muted small">By</span><span class="small"><?= e($s['publishedBy'] ?: '—') ?></span></div>
        <div class="between"><span class="muted small">Signature</span><span><?= $s['signed'] ? '<span class="status active">Signed</span>' : '<span class="status archived">Unsigned</span>' ?></span></div>
        <?php if ((string) $s['releaseUid'] !== ''): ?>
          <div class="between"><span class="muted small">Identifier</span><span class="mono"><?= e($s['releaseUid']) ?></span></div>
        <?php endif; ?>
      </div>
    </div>
  </div>
</div>
