<?php $theme = $_COOKIE['mb_theme'] ?? 'system'; ?>
<!doctype html>
<html lang="en" data-theme="<?= e($theme) ?>">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Not permitted · Skylink Bingwa Admin</title>
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@600;700&family=Poppins:wght@400;500;600&display=swap" rel="stylesheet">
<link rel="stylesheet" href="<?= e(asset('css/app.css')) ?>">
</head>
<body>
<div class="auth">
  <div class="auth__card text-center">
    <div style="color:var(--warning)"><?= icon('shield', 40) ?></div>
    <h1>You don't have permission</h1>
    <p class="sub">Your role does not include <span class="mono"><?= e($permission ?? '') ?></span>. Ask a Super Admin if you need access.</p>
    <a class="btn btn--secondary" href="<?= e(url('/')) ?>">Back to dashboard</a>
  </div>
</div>
</body>
</html>
