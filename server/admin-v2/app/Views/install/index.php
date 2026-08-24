<?php use App\Core\Csrf; $theme = $_COOKIE['mb_theme'] ?? 'system'; ?>
<!doctype html>
<html lang="en" data-theme="<?= e($theme) ?>">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Install · Skylink Bingwa Admin</title>
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@600;700;800&family=Poppins:wght@400;500;600&display=swap" rel="stylesheet">
<link rel="stylesheet" href="<?= e(asset('css/app.css')) ?>">
</head>
<body>
<div class="auth">
  <div class="auth__card" style="max-width:520px">
    <div class="auth__brand"><img class="brand__logo" src="<?= e(asset('img/logo.png')) ?>" alt="Skylink Bingwa" width="32" height="32"><span class="brand__name">Skylink <b>Bingwa</b></span></div>
    <h1><?= $installed ? 'Run database upgrade' : 'Install admin' ?></h1>
    <p class="sub"><?= $installed
      ? 'Apply any new database migrations. Safe to re-run.'
      : 'Create the tables, permissions, default roles, catalogue and the first Super Admin.' ?></p>

    <?php foreach (($flashes ?? []) as $f): ?>
      <div class="alert <?= e($f['level']) ?>" style="margin-bottom:14px"><?= e($f['message']) ?></div>
    <?php endforeach; ?>

    <?php if (!empty($generated)): ?>
      <div class="alert warning" style="margin-bottom:14px">
        <div>
          <b>Super Admin password (shown once):</b>
          <div class="mono" style="font-size:15px;margin-top:4px"><?= e($generated) ?></div>
          <div class="small mt">Sign in and change it immediately. It will not be shown again.</div>
        </div>
      </div>
    <?php endif; ?>

    <?php if (!empty($log)): ?>
      <div class="card" style="margin-bottom:14px;text-align:left">
        <?php foreach ($log as $line): ?><div class="small"><?= icon('check', 14) ?> <?= e($line) ?></div><?php endforeach; ?>
      </div>
    <?php endif; ?>

    <form method="post" action="<?= e(url('/install')) ?>" data-once>
      <?= Csrf::field() ?>
      <button class="btn btn--block" type="submit"><?= $installed ? 'Run migrations' : 'Install now' ?></button>
    </form>
    <p class="text-center mt small"><a href="<?= e(url('/login')) ?>">Go to sign in</a></p>
  </div>
</div>
<script src="<?= e(asset('js/app.js')) ?>" defer></script>
</body>
</html>
