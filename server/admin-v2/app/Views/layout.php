<?php
/** Main admin shell. $content is the page HTML. Theme is read from the mb_theme cookie. */
use App\Core\Csrf;
$theme = $_COOKIE['mb_theme'] ?? 'system';
if (!in_array($theme, ['light', 'dark', 'system'], true)) { $theme = 'system'; }
$navCollapsed = ($_COOKIE['mb_nav'] ?? '') === 'collapsed';
$authUser = $authUser ?? null;
?><!doctype html>
<html lang="en" data-theme="<?= e($theme) ?>">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title><?= e($pageTitle ?? 'Skylink Bingwa Admin') ?> · Skylink Bingwa Admin</title>
<link rel="icon" type="image/png" sizes="32x32" href="<?= e(asset('img/favicon-32x32.png')) ?>">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@500;600;700;800&family=Poppins:wght@400;500;600;700&display=swap" rel="stylesheet">
<link rel="stylesheet" href="<?= e(asset('css/app.css')) ?>">
</head>
<body>
<div class="app<?= $navCollapsed ? ' nav-collapsed' : '' ?>">
  <div class="scrim" aria-hidden="true"></div>
  <?= \App\Core\View::partial('partials/sidebar', ['activeNav' => $activeNav ?? '', 'publishStatus' => $publishStatus ?? []]) ?>
  <div class="main">
    <?= \App\Core\View::partial('partials/topbar', ['authUser' => $authUser, 'publishStatus' => $publishStatus ?? []]) ?>
    <main class="content">
      <?= $content ?>
    </main>
  </div>
</div>

<?php $flashes = $flashes ?? []; ?>
<div class="toasts" aria-live="polite">
  <?php foreach ($flashes as $f): ?>
    <div class="toast <?= e($f['level']) ?>">
      <span class="icon"><?= icon($f['level'] === 'success' ? 'check' : ($f['level'] === 'error' ? 'warning' : 'info'), 18) ?></span>
      <div><?= e($f['message']) ?></div>
      <button class="close" aria-label="Dismiss"><?= icon('close', 16) ?></button>
    </div>
  <?php endforeach; ?>
</div>

<!-- Confirmation modal for dangerous / state-changing actions -->
<div class="modal-backdrop" id="confirm-modal" role="dialog" aria-modal="true">
  <div class="modal">
    <h3 data-confirm-title>Please confirm</h3>
    <p data-confirm-body>Are you sure?</p>
    <div class="modal__actions">
      <button class="btn btn--secondary" data-confirm-cancel type="button">Cancel</button>
      <button class="btn btn--warn" data-confirm-ok type="button">Confirm</button>
    </div>
  </div>
</div>

<script src="<?= e(asset('js/app.js')) ?>" defer></script>
</body>
</html>
