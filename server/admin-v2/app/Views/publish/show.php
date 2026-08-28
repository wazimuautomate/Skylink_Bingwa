<?php
/**
 * A single published release: exactly what it contained, what each resource version was
 * on it, and its integrity values. Everything here is read from the immutable record —
 * this page never recomputes a diff.
 *
 * Rollback is unchanged: it copies this version's contents into the working draft, which
 * is then published as a NEW, later version.
 */

require_once __DIR__ . '/../preview/_changes.php';

$snap = $snapshot;
$totalChanges = 0;
foreach ($groups as $g) { $totalChanges += (int) $g['count']; }
$signed = (string) ($release['signature'] ?? '') !== '';
$uid = (string) ($release['release_uid'] ?? '');
?>
<?= mb_change_group_styles() ?>
<style>
.rl-meta { display: flex; flex-direction: column; gap: 8px; }
</style>

<div class="page-head">
  <div>
    <h1>Release v<?= (int) $release['version'] ?> <?= $isCurrent ? '<span class="tag sms">Live</span>' : '' ?></h1>
    <div class="sub">
      Published <?= e(fmt_nairobi($release['created_at'])) ?> by <?= e($release['published_by']) ?><?= $release['rolled_back_from'] ? ' · rollback of v' . (int) $release['rolled_back_from'] : '' ?>
    </div>
  </div>
  <div class="page-head__actions"><a class="btn btn--ghost" href="<?= e(url('/releases')) ?>">Back to history</a></div>
</div>

<div class="grid two">
  <div class="stack">
    <?php if (trim((string) ($release['notes'] ?? '')) !== ''): ?>
      <div class="card">
        <div class="card__head"><?= icon('info', 18) ?><h3>Release notes</h3></div>
        <p class="small"><?= nl2br(e($release['notes'])) ?></p>
      </div>
    <?php endif; ?>

    <div class="card">
      <div class="card__head"><?= icon('publish', 18) ?><h3>Changes in this version</h3><span class="spacer"></span>
        <span class="tag muted"><?= (int) $totalChanges ?> change<?= (int) $totalChanges === 1 ? '' : 's' ?></span>
      </div>
      <?php if ($groups): ?>
        <p class="small muted mb">Open a group to see the exact values this release changed.</p>
        <?= mb_change_groups($groups) ?>
      <?php else: ?>
        <div class="empty"><?= icon('layers', 32) ?><h3>No per-item changes recorded</h3>
          <p>This was the first published version (the baseline), or it was published before field-level records existed.</p>
        </div>
      <?php endif; ?>
    </div>
  </div>

  <div class="stack">
    <div class="card">
      <div class="card__head"><?= icon('sync', 18) ?><h3>Resource versions on this release</h3></div>
      <?php if (!empty($resourceVersions)): ?>
        <p class="small muted mb">A device that already held these numbers downloaded nothing for them.</p>
        <div class="res-strip">
          <?php foreach ($resourceVersions as $key => $rv): ?>
            <span class="res-chip<?= !empty($rv['changed']) ? ' is-moving' : '' ?>">
              <span><?= e(App\Services\ChangeDetector::moduleLabel((string) $key)) ?></span>
              <b>v<?= (int) ($rv['version'] ?? 0) ?></b>
              <span class="muted"><?= (int) ($rv['count'] ?? 0) ?></span>
            </span>
          <?php endforeach; ?>
        </div>
      <?php else: ?>
        <p class="small muted">This release predates per-resource versioning, so every resource was treated as changed.</p>
      <?php endif; ?>
    </div>

    <div class="card">
      <div class="card__head"><?= icon('shield', 18) ?><h3>Integrity</h3></div>
      <div class="rl-meta">
        <div class="between"><span class="muted small">Version</span><b>v<?= (int) $release['version'] ?></b></div>
        <div class="between"><span class="muted small">Schema</span><b><?= (int) $release['schema_version'] ?></b></div>
        <?php if ($uid !== ''): ?>
          <div class="between"><span class="muted small">Identifier</span><span class="mono"><?= e($uid) ?></span></div>
        <?php endif; ?>
        <div class="between"><span class="muted small">Signature</span>
          <span><?= $signed ? '<span class="status active">Signed</span>' : '<span class="status archived">Unsigned</span>' ?></span>
        </div>
        <?php if ($signed && (string) ($release['signature_algo'] ?? '') !== ''): ?>
          <div class="between"><span class="muted small">Algorithm</span><span class="small mono"><?= e($release['signature_algo']) ?></span></div>
        <?php endif; ?>
        <div class="field"><label class="small">SHA-256 checksum</label><input class="mono" type="text" value="<?= e($release['checksum']) ?>" readonly></div>
        <div class="row">
          <span class="tag muted">Offers: <?= count($snap['offers'] ?? []) ?></span>
          <span class="tag muted">Billboards: <?= count($snap['billboards'] ?? []) ?></span>
          <span class="tag muted">Categories: <?= count($snap['categories'] ?? []) ?></span>
        </div>
      </div>
    </div>

    <?php if (can('rollback.execute') && !$isCurrent): ?>
      <div class="card">
        <div class="card__head"><?= icon('rollback', 18) ?><h3>Roll back to this version</h3></div>
        <p class="small muted">Copies this version's contents into the working draft and publishes it as a new, later version. Old versions are never modified.</p>
        <form method="post" action="<?= e(url('/releases/' . (int) $release['version'] . '/rollback')) ?>" data-once
              data-confirm="Roll back to v<?= (int) $release['version'] ?>? This creates a NEW version with these contents." data-confirm-title="Confirm rollback">
          <?= App\Core\Csrf::field() ?>
          <div class="field mb"><label>Reason (required)</label><input type="text" name="reason" required placeholder="Why are you rolling back?"></div>
          <button class="btn btn--warn btn--block" type="submit"><?= icon('rollback', 18) ?> Roll back</button>
        </form>
      </div>
    <?php endif; ?>
  </div>
</div>
