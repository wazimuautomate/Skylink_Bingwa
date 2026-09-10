<?php
/**
 * The "app is blocked" notice shown on top of the dashboard while the service lock is on.
 *
 * Rendered with `open` already on the backdrop so it is visible the moment the page
 * loads — the admin CSP is script-src 'self', so nothing here may open it from an inline
 * script. app.js already wires [data-modal-close] and Escape on any .modal-backdrop[data-modal].
 *
 * Expects: $serviceLock (the App\Services\ServiceLock::state() array) and $isSuperAdmin.
 */
$lock = $serviceLock ?? [];
if (empty($lock['enabled'])) {
    return;
}
$amount = (int) ($lock['amount'] ?? 0);
?>
<div class="modal-backdrop open" data-modal id="service-lock-notice" role="dialog" aria-modal="true"
     aria-labelledby="service-lock-title">
  <div class="modal modal--danger">
    <h3 id="service-lock-title"><?= icon('warning', 20) ?> The app cannot be used</h3>

    <?php if ($amount > 0): ?>
      <p>The app and the server are blocked because of a pending payment of</p>
      <strong class="lock-amount">Ksh <?= number_format($amount) ?></strong>
    <?php else: ?>
      <p>The app and the server are blocked.</p>
    <?php endif; ?>

    <p><?= e($lock['reason'] ?? '') ?></p>

    <p class="lock-meta">
      Every request from the app is being refused with “Request Denied”: no purchases, no
      offers, no sync, no registration, no referrals and no withdrawals.
      <?php if (!empty($lock['since'])): ?>
        Blocked since <?= e(fmt_nairobi($lock['since'])) ?>.
      <?php endif; ?>
    </p>

    <div class="modal__actions">
      <?php if (!empty($isSuperAdmin)): ?>
        <a class="btn btn--secondary" href="<?= e(url('/danger-zone')) ?>">Open danger zone</a>
      <?php endif; ?>
      <button class="btn btn--warn" data-modal-close type="button">I understand</button>
    </div>
  </div>
</div>
