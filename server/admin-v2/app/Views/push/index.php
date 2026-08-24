<?php
use App\Core\Csrf;

/**
 * Instant Push Notifications View.
 *
 * Form to compose and instantly broadcast push notifications to all registered devices.
 */
?>

<div class="page-head">
  <div>
    <h1>Instant Push Notifications</h1>
    <div class="sub">Send real-time push notifications directly to user phones via Firebase Cloud Messaging (FCM HTTP v1).</div>
  </div>
</div>

<div class="grid grid--2" style="gap: 1.5rem; margin-bottom: 2rem;">
  <!-- Setup Status & Stats Card -->
  <div class="card">
    <h3 style="margin-top: 0; margin-bottom: 0.75rem; display: flex; align-items: center; gap: 0.5rem;">
      <?= icon('bell', 20) ?> Service Status
    </h3>
    <div style="margin-bottom: 1rem;">
      <?php if ($isConfigured): ?>
        <span class="badge badge--success" style="font-size: 0.9rem; padding: 0.35rem 0.75rem;">
          ✓ Firebase Service Account Connected
        </span>
      <?php else: ?>
        <span class="badge badge--danger" style="font-size: 0.9rem; padding: 0.35rem 0.75rem;">
          ✕ Firebase Credentials Missing
        </span>
        <p class="text-muted" style="font-size: 0.85rem; margin-top: 0.5rem;">
          Place the Firebase service-account JSON in the server root or set <code>fcm.service_account_file</code> in <code>config.php</code>.
        </p>
        <?php if (!empty($configError)): ?>
          <p class="text-muted" style="font-size: 0.8rem; margin-top: 0.5rem; word-break: break-all;"><?= e($configError) ?></p>
        <?php endif; ?>
      <?php endif; ?>
    </div>

    <div style="display: flex; gap: 2rem; border-top: 1px solid var(--border-color, #e2e8f0); padding-top: 1rem;">
      <div>
        <div style="font-size: 0.85rem; color: var(--text-muted, #64748b);">Active FCM Tokens</div>
        <div style="font-size: 1.75rem; font-weight: 700; color: var(--brand-color, #00C853);"><?= number_format($tokenCount) ?></div>
      </div>
      <div>
        <div style="font-size: 0.85rem; color: var(--text-muted, #64748b);">Target Audience</div>
        <div style="font-size: 1.1rem; font-weight: 600; margin-top: 0.35rem;">All Registered Customers</div>
      </div>
    </div>
  </div>

  <!-- Compose & Send Form -->
  <div class="card">
    <h3 style="margin-top: 0; margin-bottom: 0.75rem; display: flex; align-items: center; gap: 0.5rem;">
      <?= icon('send', 20) ?> Compose Notification
    </h3>

    <form method="POST" action="<?= e(url('/push/send')) ?>">
      <?= Csrf::field() ?>

      <div class="form-group" style="margin-bottom: 1rem;">
        <label for="push_title" style="display: block; font-weight: 600; margin-bottom: 0.35rem;">Notification Title *</label>
        <input
          type="text"
          id="push_title"
          name="title"
          class="form-control"
          placeholder="e.g. Flash Offer Today Only!"
          maxlength="120"
          required
          style="width: 100%; padding: 0.6rem; border: 1px solid var(--border-color, #cbd5e1); border-radius: 6px;"
        >
      </div>

      <div class="form-group" style="margin-bottom: 1rem;">
        <label for="push_body" style="display: block; font-weight: 600; margin-bottom: 0.35rem;">Message Body *</label>
        <textarea
          id="push_body"
          name="body"
          class="form-control"
          rows="3"
          placeholder="e.g. 1.5GB valid 3 Hrs for KSh 50. Tap now to buy before offer ends!"
          maxlength="500"
          required
          style="width: 100%; padding: 0.6rem; border: 1px solid var(--border-color, #cbd5e1); border-radius: 6px;"
        ></textarea>
      </div>

      <div class="form-group" style="margin-bottom: 1.25rem;">
        <label for="push_route" style="display: block; font-weight: 600; margin-bottom: 0.35rem;">When Tapped, Open Screen:</label>
        <select
          id="push_route"
          name="route"
          class="form-control"
          style="width: 100%; padding: 0.6rem; border: 1px solid var(--border-color, #cbd5e1); border-radius: 6px;"
        >
          <?php
            $routeLabels = [
              'notifications' => 'Notifications Center',
              'offers'        => 'Offers Catalogue',
              'home'          => 'Home Screen',
              'activity'      => 'Activity History',
              'help'          => 'Support & Help',
            ];
            foreach (($routes ?? array_keys($routeLabels)) as $r):
          ?>
            <option value="<?= e($r) ?>"><?= e($routeLabels[$r] ?? ucfirst($r)) ?></option>
          <?php endforeach; ?>
        </select>
      </div>

      <button
        type="submit"
        class="btn btn--primary"
        style="width: 100%; padding: 0.75rem; font-size: 1rem; font-weight: 600; cursor: pointer; background: #00C853; color: white; border: none; border-radius: 6px;"
        <?= !$isConfigured ? 'disabled' : '' ?>
      >
        <?= icon('send', 18) ?> Send Instant Push Notification
      </button>
    </form>
  </div>
</div>

<!-- Broadcast History Table -->
<div class="card">
  <h3 style="margin-top: 0; margin-bottom: 1rem; display: flex; align-items: center; gap: 0.5rem;">
    <?= icon('clock', 20) ?> Recent Broadcasts
  </h3>

  <?php if (!empty($historyError)): ?>
    <div class="empty" style="text-align: center; padding: 2rem; color: #b45309;">
      <p><?= e($historyError) ?></p>
      <p style="font-size: 0.85rem;">Migrations apply automatically on the next page load. If this persists, the migration is failing &mdash; check the PHP error log for <code>auto-migrate failed</code>.</p>
    </div>
  <?php elseif (empty($history)): ?>
    <div class="empty" style="text-align: center; padding: 2rem; color: var(--text-muted, #64748b);">
      <p>No push notifications sent yet. Use the form above to dispatch your first push notification.</p>
    </div>
  <?php else: ?>
    <div class="table-responsive">
      <table class="table" style="width: 100%; border-collapse: collapse;">
        <thead>
          <tr style="text-align: left; border-bottom: 2px solid var(--border-color, #e2e8f0); color: var(--text-muted, #64748b);">
            <th style="padding: 0.75rem;">Time</th>
            <th style="padding: 0.75rem;">Title</th>
            <th style="padding: 0.75rem;">Message Body</th>
            <th style="padding: 0.75rem;">Route</th>
            <th style="padding: 0.75rem;">Delivered</th>
            <th style="padding: 0.75rem;">Sender</th>
          </tr>
        </thead>
        <tbody>
          <?php foreach ($history as $row): ?>
            <tr style="border-bottom: 1px solid var(--border-color, #f1f5f9);">
              <td style="padding: 0.75rem; white-space: nowrap; font-size: 0.85rem;"><?= e(date('M j, Y H:i', strtotime($row['created_at']))) ?></td>
              <td style="padding: 0.75rem; font-weight: 600;"><?= e($row['title']) ?></td>
              <td style="padding: 0.75rem; max-width: 300px; font-size: 0.9rem;"><?= e($row['body']) ?></td>
              <td style="padding: 0.75rem;"><span class="badge"><?= e($row['deep_link_route']) ?></span></td>
              <td style="padding: 0.75rem; font-size: 0.9rem;">
                <span style="color: #00C853; font-weight: 600;"><?= e($row['success_count']) ?></span>
                <?php if ($row['failure_count'] > 0): ?>
                  / <span style="color: #e53935; font-size: 0.85rem;"><?= e($row['failure_count']) ?> failed</span>
                <?php endif; ?>
              </td>
              <td style="padding: 0.75rem; font-size: 0.85rem; color: var(--text-muted, #64748b);"><?= e($row['created_by']) ?></td>
            </tr>
          <?php endforeach; ?>
        </tbody>
      </table>
    </div>
  <?php endif; ?>
</div>
