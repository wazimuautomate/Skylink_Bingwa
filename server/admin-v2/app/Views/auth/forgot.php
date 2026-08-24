<?php use App\Core\Csrf; $theme = $_COOKIE['mb_theme'] ?? 'system'; ?>
<!doctype html>
<html lang="en" data-theme="<?= e($theme) ?>">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Account recovery · Skylink Bingwa Admin</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@600;700&family=Poppins:wght@400;500;600&display=swap" rel="stylesheet">
<link rel="stylesheet" href="<?= e(asset('css/app.css')) ?>">
</head>
<body>
<div class="auth">
  <div class="auth__card">
    <div class="auth__brand"><img class="brand__logo" src="<?= e(asset('img/logo.png')) ?>" alt="Skylink Bingwa" width="32" height="32"><span class="brand__name">Skylink <b>Bingwa</b></span></div>
    <h1>Account recovery</h1>
    <p class="sub">Ask the Super Admin to set you a new password from <b>Settings → Manage partner Admin</b>.</p>
    <?php foreach (($flashes ?? []) as $f): ?>
      <div class="alert <?= e($f['level']) ?>" style="margin-bottom:14px"><?= e($f['message']) ?></div>
    <?php endforeach; ?>
    <p class="small muted">If you are the Super Admin and cannot sign in, reset the password directly on the server (the account row in <span class="mono">mb_admin_users</span>) or re-run the installer.</p>
    <p class="text-center mt small">
      <a href="<?= e(url('/login')) ?>">Back to sign in</a>
    </p>
  </div>
</div>
<script src="<?= e(asset('js/app.js')) ?>" defer></script>
</body>
</html>
