<?php
/**
 * Danger zone — the Super-Admin-only service lock.
 *
 * One switch, an optional amount, an optional reason, and a Save that asks for
 * confirmation (the shell's data-confirm modal). Saving takes effect on the very next
 * request the app makes: there is nothing to publish and nothing to deploy.
 */
$locked = (bool) $lock['enabled'];
$amount = (int) $lock['amount'];
?>
<style>
  /* Page-scoped: this page is deliberately the only red one in the panel. */
  .danger-head { border-left: 4px solid var(--error); padding-left: 14px; }
  .danger-card { border-color: var(--error); }
  .danger-state { display: flex; align-items: center; gap: 12px; padding: 14px 16px;
    border-radius: var(--radius-sm); font-weight: 600; }
  .danger-state.on  { background: var(--error-soft); color: var(--error); }
  .danger-state.off { background: var(--success-soft); color: var(--success); }
  .danger-state .dot { width: 10px; height: 10px; border-radius: 50%; background: currentColor; flex: none; }
  .danger-preview { background: var(--grouped); border-radius: var(--radius-sm); padding: 14px 16px; }
  .danger-preview__title { color: var(--error); font-weight: 700; font-family: var(--font-title); }
  .danger-preview__body { font-size: 13px; color: var(--text-2); margin-top: 4px; }
</style>

<div class="page-head">
  <div class="danger-head">
    <h1>Danger zone</h1>
    <div class="sub">Block the app and this server completely. Super Admin only.</div>
  </div>
</div>

<div class="grid two">
  <div class="stack">
    <div class="card danger-card">
      <div class="card__head"><?= icon('warning', 18) ?><h3>Service lock</h3></div>

      <div class="danger-state <?= $locked ? 'on' : 'off' ?>">
        <span class="dot"></span>
        <?php if ($locked): ?>
          <span>ON — every app and server request is being refused.</span>
        <?php else: ?>
          <span>OFF — the app and the server are working normally.</span>
        <?php endif; ?>
      </div>

      <?php if ($locked && $lock['since']): ?>
        <p class="small muted mt">Locked <?= e(fmt_nairobi($lock['since'])) ?><?= $lock['by'] !== '' ? ' by ' . e($lock['by']) : '' ?>.</p>
      <?php endif; ?>

      <form class="mt" method="post" action="<?= e(url('/danger-zone/save')) ?>"
            data-confirm-title="<?= $locked ? 'Change the service lock?' : 'Block the app and the server?' ?>"
            data-confirm="This takes effect immediately. While the lock is on, the app cannot buy bundles, read offers, sync, register, request an OTP, view referrals or withdraw — every request is refused with “Request Denied”.">
        <?= App\Core\Csrf::field() ?>

        <div class="field">
          <label class="switch">
            <input type="checkbox" name="enabled" value="1" <?= $locked ? 'checked' : '' ?>>
            <span class="track"></span>
            <span>Block the app and the server</span>
          </label>
          <span class="hint">When this is on, nothing can reach the app and the app can reach nothing.</span>
        </div>

        <div class="form-grid mt">
          <div class="field">
            <label for="amount">Pending payment (KSh) — optional</label>
            <input id="amount" type="number" name="amount" min="0" step="1" inputmode="numeric"
                   value="<?= $amount > 0 ? (int) $amount : '' ?>" placeholder="Leave empty to name no amount">
            <span class="hint">Shown on the dashboard notice and returned with every refused request.</span>
          </div>
          <div class="field full">
            <label for="reason">Reason — optional</label>
            <textarea id="reason" name="reason" rows="3"
                      placeholder="<?= e($defaultReason) ?>"><?= e($lock['reason'] === $defaultReason ? '' : $lock['reason']) ?></textarea>
            <span class="hint">Leave this empty to use the default wording shown above.</span>
          </div>
        </div>

        <div class="mt">
          <button class="btn <?= $locked ? 'btn--secondary' : 'btn--danger' ?>" type="submit">
            Save and apply now
          </button>
        </div>
      </form>
    </div>
  </div>

  <div class="stack">
    <div class="card">
      <div class="card__head"><?= icon('eye', 18) ?><h3>What a blocked request sees</h3></div>
      <div class="danger-preview">
        <div class="danger-preview__title">Request Denied</div>
        <div class="danger-preview__body"><?= e(App\Services\ServiceLock::message($lock)) ?></div>
      </div>
      <p class="small muted mt">Returned as HTTP 503 to every app-facing endpoint.</p>
    </div>

    <div class="card">
      <div class="card__head"><?= icon('shield', 18) ?><h3>What stays running</h3></div>
      <p class="small muted">
        Safaricom's payment callbacks are never blocked. They report money that has already
        moved, so refusing them would leave a real customer's payment unreconciled. No new
        payment can start while the lock is on, so nothing new enters that path.
      </p>
      <p class="small muted mt">This admin panel itself is never blocked.</p>
    </div>
  </div>
</div>
