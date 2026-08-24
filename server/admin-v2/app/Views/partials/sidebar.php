<?php
/** Left navigation. Items are page-gated (hiding is UX only; the server enforces). */
$nav = [
    ['dashboard',     'Dashboard',         '/',                 'dashboard.view',      'dashboard'],
    ['offers',        'Offers',            '/offers',           'offers.view',         'offers'],
    ['billboards',    'Billboard adverts', '/billboards',       'billboards.manage',   'billboards'],
    ['notifications', 'Notifications',     '/notifications',    'notifications.create','notifications'],
    ['send',          'Instant Push',      '/push',             'notifications.create','push'],
    ['payments',      'Payments',          '/payments',         'payments.view',       'payments'],
    ['user',          'Customers',         '/customers',        'customers.view',      'customers'],
    ['support',       'Support details',   '/support',          'support.edit',        'support'],
    ['config',        'App configuration', '/app-config',       'config.edit',         'config'],
    ['versions',      'Updates & versions','/versions',         'releases.manage',     'versions'],
    ['publish',       'Preview & publish', '/preview',          'publish.execute',     'preview'],
    ['audit',         'Audit log',         '/audit',            'audit.view',          'audit'],
    ['settings',      'Settings',          '/settings',         null,                  'settings'],
];
$draftCount = (int) ($publishStatus['draftCount'] ?? 0);
?>
<aside class="sidebar">
  <a class="brand" href="<?= e(url('/')) ?>">
    <img class="brand__logo" src="<?= e(asset('img/logo.png')) ?>" alt="Skylink Bingwa" width="32" height="32">
    <span class="brand__name">Skylink <b>Bingwa</b></span>
  </a>

  <nav class="nav" aria-label="Primary">
    <?php foreach ($nav as [$icon, $label, $path, $perm, $key]): ?>
      <?php if ($perm !== null && !can($perm)) { continue; } ?>
      <a class="nav__item <?= ($activeNav ?? '') === $key ? 'is-active' : '' ?>" href="<?= e(url($path)) ?>" title="<?= e($label) ?>">
        <?= icon($icon, 20) ?>
        <span><?= e($label) ?></span>
        <?php if ($key === 'preview' && $draftCount > 0): ?><span class="nav__badge"><?= $draftCount ?></span><?php endif; ?>
      </a>
    <?php endforeach; ?>
  </nav>

  <div class="nav__footer">
    <form method="post" action="<?= e(url('/logout')) ?>">
      <?= App\Core\Csrf::field() ?>
      <button class="nav__item" type="submit" style="width:100%;border:0;background:0;cursor:pointer;font:inherit">
        <?= icon('logout', 20) ?><span>Sign out</span>
      </button>
    </form>
  </div>
</aside>
