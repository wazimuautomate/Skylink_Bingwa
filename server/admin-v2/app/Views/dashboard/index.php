<?php
/**
 * Dashboard — offer and bundle performance.
 * Four cards (revenue, category sales today, buying trend, total offers), each one
 * a link to the page that holds the detail; then best performing bundles, the last
 * fourteen days of trade, and the latest payments. Phone numbers stay masked here.
 */

use App\Repositories\PaymentRepository;

$avail = (bool) $paymentsAvailable;

/** Money and counts read "—" until the first payment exists, never a PHP warning. */
$money = fn($v) => $avail ? ksh($v) : '—';
$num   = fn($v) => $avail ? number_format((int) $v) : '—';

$zeroNote = 'Figures appear once the first payment arrives.';

$todayQs = 'from=' . rawurlencode($today) . '&to=' . rawurlencode($today);
$qs30    = 'from=' . rawurlencode($from30) . '&to=' . rawurlencode($today);
$qs14    = 'from=' . rawurlencode($from14) . '&to=' . rawurlencode($today);

$cats = ['DATA' => 'Data', 'SMS' => 'SMS', 'MINUTES' => 'Minutes', 'SPECIAL' => 'Special'];
$otherSales = (int) ($categoryToday['OTHER']['sales'] ?? 0);

$selfShare  = (int) ($buyerAllTime['selfShare'] ?? 0);
$otherShare = ((int) ($buyerAllTime['total'] ?? 0)) > 0 ? 100 - $selfShare : 0;

/* Bars follow revenue; if a whole fortnight earned nothing, they follow sales so
   the shape of the trade is still visible. */
$seriesSales   = array_sum($series['sales']);
$seriesRevenue = array_sum($series['revenue']);
$barValues = $seriesRevenue > 0 ? $series['revenue'] : $series['sales'];
$barMax    = max(array_merge([1], $barValues));

$tagClass = static function (string $category): string {
    $c = strtolower($category);
    return in_array($c, ['data', 'sms', 'minutes', 'special'], true) ? $c : 'muted';
};
?>
<?php /* Shown on top of the dashboard only while the service lock is on; renders nothing otherwise. */ ?>
<?= App\Core\View::partial('partials/service_lock_notice', [
      'serviceLock'  => $serviceLock ?? [],
      'isSuperAdmin' => $isSuperAdmin ?? false,
    ]) ?>
<style>
  /* Page-scoped: clickable cards, the four-way tile split and the 14-day bars. */
  .dash-card { display: block; color: inherit; text-decoration: none;
    transition: border-color var(--t-fast), box-shadow var(--t-fast); }
  .dash-card:hover { text-decoration: none; border-color: var(--green); box-shadow: var(--shadow); }
  .dash-card:focus-visible { outline: 2px solid var(--blue); outline-offset: 2px; }
  .dash-head { text-decoration: none; }
  .dash-head:hover { text-decoration: none; color: var(--text); }
  .dash-head:focus-visible { outline: 2px solid var(--blue); outline-offset: 2px; }

  .quad { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 2px; }
  .quad__cell { display: block; padding: 10px 12px; border-radius: var(--radius-sm);
    background: var(--grouped); color: var(--text); text-decoration: none;
    transition: background var(--t-fast), box-shadow var(--t-fast); }
  .quad__cell:hover { text-decoration: none; background: var(--surface); box-shadow: inset 0 0 0 1px var(--green); }
  .quad__cell:focus-visible { outline: 2px solid var(--blue); outline-offset: 2px; }
  .quad__n { display: block; font-family: var(--font-title); font-size: 20px; font-weight: 700;
    letter-spacing: -.3px; margin-top: 4px; }
  .quad__sub { display: block; font-size: 12px; color: var(--text-2); }

  .trend { display: flex; align-items: flex-end; gap: 5px; height: 112px; margin: 4px 0 6px; }
  .trend__col { flex: 1 1 0; height: 100%; display: flex; align-items: flex-end;
    background: var(--grouped); border-radius: 5px; overflow: hidden; }
  .trend__fill { display: block; width: 100%; background: var(--green); border-radius: 5px; }
  .stat > .bar, .stat > .quad { flex: none; }

  .perf-link { display: block; color: var(--text); font-weight: 600; text-decoration: none; }
  .perf-link:hover { text-decoration: underline; }
</style>

<div class="page-head">
  <div>
    <h1><?= e($greeting) ?>, <?= e($firstName) ?></h1>
    <div class="sub">What your offers are actually selling.</div>
  </div>
  <div class="page-head__actions">
    <a class="btn btn--secondary" href="<?= e(url('/dashboard/export')) ?>"><?= icon('download', 18) ?> Export CSV</a>
    <a class="btn" href="<?= e(url('/offers/new')) ?>"><?= icon('plus', 18) ?> Add offer</a>
  </div>
</div>

<!-- the four cards -->
<div class="grid cards mb">

  <!-- 1. Total revenue: today and all time -->
  <a class="card dash-card" href="<?= e(url('/payments')) ?>">
    <div class="stat">
      <span class="stat__label">Total revenue <span class="stat__icon green"><?= icon('money', 18) ?></span></span>
      <span class="stat__value"><?= e($money($revenue['todayRevenue'])) ?> <small>today</small></span>
      <span class="between">
        <span class="small muted">All time</span>
        <b class="nowrap"><?= e($money($revenue['totalRevenue'])) ?></b>
      </span>
      <span class="small muted">
        <?php if ($avail): ?>
          <?= e(number_format((int) $revenue['todaySales'])) ?> today ·
          <?= e(number_format((int) $revenue['totalSales'])) ?> all time ·
          avg <?= e(ksh($revenue['averageSale'])) ?>
        <?php else: ?>
          <?= e($zeroNote) ?>
        <?php endif; ?>
      </span>
    </div>
  </a>

  <!-- 2. Category sales today -->
  <div class="card">
    <div class="stat">
      <a class="stat__label dash-head" href="<?= e(url('/payments')) ?>">
        Category sales today <span class="stat__icon blue"><?= icon('layers', 18) ?></span>
      </a>
      <div class="quad">
        <?php foreach ($cats as $key => $label): ?>
          <?php $c = $categoryToday[$key] ?? ['sales' => 0, 'revenue' => 0]; ?>
          <a class="quad__cell" href="<?= e(url('/payments?category=' . rawurlencode($key) . '&' . $todayQs)) ?>">
            <span class="tag <?= e(strtolower($key)) ?>"><?= e($label) ?></span>
            <span class="quad__n"><?= e($num($c['sales'])) ?></span>
            <span class="quad__sub"><?= e($money($c['revenue'])) ?></span>
          </a>
        <?php endforeach; ?>
      </div>
      <?php if (!$avail): ?>
        <span class="small muted"><?= e($zeroNote) ?></span>
      <?php elseif ($otherSales > 0): ?>
        <span class="small muted"><?= e(number_format($otherSales)) ?> more from offers no longer in the catalogue.</span>
      <?php endif; ?>
    </div>
  </div>

  <!-- 3. Buying trend: for myself vs for another -->
  <div class="card">
    <div class="stat">
      <a class="stat__label dash-head" href="<?= e(url('/payments')) ?>">
        Buying trend <span class="stat__icon blue"><?= icon('user', 18) ?></span>
      </a>
      <div class="quad">
        <a class="quad__cell" href="<?= e(url('/payments?buyer=self')) ?>">
          <span class="quad__sub">For myself</span>
          <span class="quad__n"><?= e($num($buyerAllTime['self']['sales'])) ?></span>
          <span class="quad__sub"><?= $avail ? e($selfShare . '% of sales') : '&mdash;' ?></span>
        </a>
        <a class="quad__cell" href="<?= e(url('/payments?buyer=other')) ?>">
          <span class="quad__sub">For another</span>
          <span class="quad__n"><?= e($num($buyerAllTime['other']['sales'])) ?></span>
          <span class="quad__sub"><?= $avail ? e($otherShare . '% of sales') : '&mdash;' ?></span>
        </a>
      </div>
      <div class="bar" role="img" aria-label="<?= e($avail ? $selfShare . '% of all sales were bought for the payer, ' . $otherShare . '% for someone else' : 'No sales yet') ?>">
        <span style="width: <?= (int) ($avail ? $selfShare : 0) ?>%"></span>
      </div>
      <span class="small muted">
        <?php if ($avail): ?>
          Today: <?= e(number_format((int) $buyerToday['self']['sales'])) ?> for myself ·
          <?= e(number_format((int) $buyerToday['other']['sales'])) ?> for another
        <?php else: ?>
          <?= e($zeroNote) ?>
        <?php endif; ?>
      </span>
    </div>
  </div>

  <!-- 4. Total offers -->
  <a class="card dash-card" href="<?= e(url('/offers')) ?>">
    <div class="stat">
      <span class="stat__label">Total offers <span class="stat__icon blue"><?= icon('offers', 18) ?></span></span>
      <span class="stat__value"><?= e(number_format((int) $offerCounts['active'])) ?> <small>active</small></span>
      <span class="between">
        <span class="small muted">In the catalogue</span>
        <b class="nowrap"><?= e(number_format((int) $offerCounts['total'])) ?></b>
      </span>
      <span class="small muted">Active offers are the only ones the app can sell.</span>
    </div>
  </a>
</div>

<!-- performance -->
<div class="grid two mb">

  <!-- best performing bundles -->
  <div class="card">
    <div class="card__head">
      <h2>Best performing bundles</h2>
      <span class="sub">Last 30 days</span>
      <span class="spacer"></span>
      <a class="small" href="<?= e(url('/payments')) ?>">All payments</a>
    </div>
    <?php if ($avail && $topOffers): ?>
      <div class="table-wrap">
        <table class="data">
          <thead><tr>
            <th>Bundle</th><th class="text-right">Sales</th><th class="text-right">Revenue</th><th class="text-right">Completed</th>
          </tr></thead>
          <tbody>
            <?php foreach ($topOffers as $o): ?>
              <tr>
                <td>
                  <a class="perf-link" href="<?= e(url('/payments?q=' . rawurlencode($o['offer_id']))) ?>">
                    <?= e($o['name'] !== '' ? $o['name'] : $o['offer_id']) ?>
                  </a>
                  <span class="small">
                    <?php if ($o['category'] !== ''): ?>
                      <span class="tag <?= e($tagClass($o['category'])) ?>"><?= e($o['category']) ?></span>
                    <?php endif; ?>
                    <span class="mono muted"><?= e($o['offer_id']) ?></span>
                  </span>
                </td>
                <td class="text-right"><?= e(number_format((int) $o['sales'])) ?></td>
                <td class="text-right nowrap"><?= e(ksh($o['revenue'])) ?></td>
                <td class="text-right"><?= (int) $o['conversion'] ?>%</td>
              </tr>
            <?php endforeach; ?>
          </tbody>
        </table>
      </div>
      <p class="small muted mt">Completed is the share of payment attempts for that bundle that
        ended in a received payment.</p>
    <?php else: ?>
      <div class="empty">
        <?= icon('offers', 32) ?>
        <h3>No bundle sales yet</h3>
        <p><?= e($zeroNote) ?></p>
      </div>
    <?php endif; ?>
  </div>

  <!-- last 14 days -->
  <div class="card">
    <div class="card__head">
      <h2>Last 14 days</h2>
      <span class="spacer"></span>
      <a class="small" href="<?= e(url('/payments?' . $qs14)) ?>">Open</a>
    </div>
    <div class="trend">
      <?php foreach ($series['labels'] as $i => $label): ?>
        <?php
          $daySales   = (int) ($series['sales'][$i] ?? 0);
          $dayRevenue = (int) ($series['revenue'][$i] ?? 0);
          $value      = (int) ($barValues[$i] ?? 0);
          $height     = $value > 0 ? max(4, (int) round($value * 100 / $barMax)) : 0;
          $caption    = $label . ': ' . number_format($daySales) . ' sales, ' . ksh($dayRevenue);
        ?>
        <div class="trend__col" role="img" title="<?= e($caption) ?>" aria-label="<?= e($caption) ?>">
          <?php if ($height > 0): ?>
            <span class="trend__fill" style="height: <?= $height ?>%"></span>
          <?php endif; ?>
        </div>
      <?php endforeach; ?>
    </div>
    <div class="between small muted">
      <span><?= e((string) ($series['labels'][0] ?? '')) ?></span>
      <span><?= e((string) ($series['labels'][count($series['labels']) - 1] ?? '')) ?></span>
    </div>
    <div class="stack mt" style="gap: 8px">
      <div class="between"><span class="small muted">Sales</span><b><?= e($num($seriesSales)) ?></b></div>
      <div class="between"><span class="small muted">Revenue</span><b class="nowrap"><?= e($money($seriesRevenue)) ?></b></div>
      <div class="between">
        <span class="small muted">Completed attempts (30 days)</span>
        <b><?= $avail ? e($status30['successRate'] . '%') : '&mdash;' ?></b>
      </div>
    </div>
    <p class="small muted mt">
      <?php if ($avail): ?>
        <a href="<?= e(url('/payments?' . $qs30)) ?>"><?= e(number_format((int) $status30['confirmed'])) ?>
          of <?= e(number_format((int) $status30['total'])) ?> payment attempts</a> completed in the last 30 days.
      <?php else: ?>
        <?= e($zeroNote) ?>
      <?php endif; ?>
    </p>
  </div>
</div>

<!-- latest payments -->
<div class="card">
  <div class="card__head"><h2>Latest payments</h2><span class="spacer"></span><a class="small" href="<?= e(url('/payments')) ?>">See all</a></div>
  <?php if ($avail && $latestPayments): ?>
    <div class="table-wrap">
      <table class="data">
        <thead><tr><th>Time</th><th>Payer</th><th>Offer</th><th>Amount</th><th>Status</th></tr></thead>
        <tbody>
          <?php foreach ($latestPayments as $p): $st = PaymentRepository::displayState($p['status']); ?>
            <tr>
              <td class="muted"><?= e(PaymentRepository::nairobiTime($p['created_at'], 'd M H:i')) ?></td>
              <td class="mono"><?= e(str_mask_phone($p['payer'])) ?></td>
              <td><?= e($p['offer_id']) ?></td>
              <td class="nowrap"><?= e(ksh($p['amount'])) ?></td>
              <td><span class="status <?= e($st['class']) ?>"><?= e($st['label']) ?></span></td>
            </tr>
          <?php endforeach; ?>
        </tbody>
      </table>
    </div>
  <?php else: ?>
    <div class="empty"><?= icon('payments', 32) ?><h3>No payments yet</h3><p>Confirmed and pending payments will appear here.</p></div>
  <?php endif; ?>
</div>
