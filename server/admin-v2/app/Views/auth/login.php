<?php use App\Core\Csrf; $theme = $_COOKIE['mb_theme'] ?? 'system'; ?>
<!doctype html>
<html lang="en" data-theme="<?= e($theme) ?>">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Sign in · Skylink Bingwa Admin</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@600;700;800&family=Poppins:wght@400;500;600&display=swap" rel="stylesheet">
<link rel="stylesheet" href="<?= e(asset('css/app.css')) ?>">
</head>
<body>
<div class="auth">
  <div class="auth__card">
    <div class="auth__brand"><img class="brand__logo" src="<?= e(asset('img/logo.png')) ?>" alt="Skylink Bingwa" width="32" height="32"><span class="brand__name">Skylink <b>Bingwa</b></span></div>
    <h1>Admin sign in</h1>
    <p class="sub">Control centre for offers, adverts and app configuration.</p>

    <?php foreach (($flashes ?? []) as $f): ?>
      <div class="alert <?= e($f['level']) ?>" style="margin-bottom:14px"><?= e($f['message']) ?></div>
    <?php endforeach; ?>

    <form method="post" action="<?= e(url('/login')) ?>" data-once>
      <?= Csrf::field() ?>
      <div class="field mb">
        <label for="email">Email</label>
        <input id="email" type="email" name="email" autocomplete="username" autofocus required>
      </div>
      <div class="field mb">
        <label for="password">Password</label>
        <input id="password" type="password" name="password" autocomplete="current-password" required>
      </div>
      <button class="btn btn--block" type="submit">Sign in</button>
    </form>
    <p class="text-center mt small"><a href="<?= e(url('/forgot')) ?>">Forgot password?</a></p>
  </div>
</div>
<script src="<?= e(asset('js/app.js')) ?>" defer></script>
</body>
</html>
