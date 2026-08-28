<?php
/**
 * Referrers — everyone who owns a referral code, ordered by what they are owed.
 *
 * Balance first, because that column is the business's liability and the thing
 * most likely to need a decision.
 */
use App\Repositories\CustomerRepository;

$ksh = static fn(int $cents): string => 'Ksh ' . number_format($cents / 100, 2);
$queryState = array_filter([
    'q' => $filters['q'],
    'status' => $filters['status'],
    'withdrawable' => !empty($filters['withdrawable']) ? '1' : '',
], static fn($v): bool => (string) $v !== '');

$link = static function (array $overrides = []) use ($queryState): string {
    $q = array_filter(array_merge($queryState, $overrides), static fn($v): bool => (string) $v !== '');
    return url('/referrals/referrers' . ($q ? '?' . http_build_query($q) : ''));
};

$badge = static function (string $status): string {
    $map = [
        'ACTIVE'         => ['Active', 'green'],
        'EARN_BLOCKED'   => ['Earning blocked', 'red'],
        'PAYOUT_BLOCKED' => ['Payout held', 'amber'],
        'BANNED'         => ['Banned', 'red'],
    ];
    [$label, $tone] = $map[$status] ?? [$status, 'muted'];
    return '<span class="tag ' . $tone . '">' . e($label) . '</span>';
};
?>

<div class="page-head">
  <div>
    <h1>Referrers</h1>
    <div class="sub"><?= number_format((int) $total) ?> customer(s) with a referral code.</div>
  </div>
  <div class="page-head__actions">
    <a class="btn btn--ghost" href="<?= e(url('/referrals')) ?>"><?= icon('chevron', 18) ?> Overview</a>
    <a class="btn btn--ghost" href="<?= e(url('/referrals-export' . ($queryState ? '?' . http_build_query($queryState) : ''))) ?>">
      <?= icon('download', 18) ?> Export CSV
    </a>
  </div>
</div>

<div class="card">
  <form class="pay-filters" method="get" action="<?= e(url('/referrals/referrers')) ?>">
    <div class="field pay-search">
      <label for="r-q">Search</label>
      <input id="r-q" type="search" name="q" value="<?= e($filters['q']) ?>" placeholder="Name, number or code (SK391R)">
    </div>
    <div class="field">
      <label for="r-status">Status</label>
      <select id="r-status" name="status">
        <option value="">Any</option>
        <?php foreach (['ACTIVE' => 'Active', 'EARN_BLOCKED' => 'Earning blocked', 'PAYOUT_BLOCKED' => 'Payout held', 'BANNED' => 'Banned'] as $k => $v): ?>
          <option value="<?= e($k) ?>" <?= $filters['status'] === $k ? 'selected' : '' ?>><?= e($v) ?></option>
        <?php endforeach; ?>
      </select>
    </div>
    <div class="field">
      <label>&nbsp;</label>
      <div class="row" style="gap:8px">
        <label class="row small" style="gap:6px">
          <input type="checkbox" name="withdrawable" value="1" <?= !empty($filters['withdrawable']) ? 'checked' : '' ?>>
          With a balance
        </label>
        <button class="btn" type="submit"><?= icon('search', 16) ?> Filter</button>
        <?php if ($queryState): ?>
          <a class="btn btn--ghost" href="<?= e(url('/referrals/referrers')) ?>">Clear</a>
        <?php endif; ?>
      </div>
    </div>
  </form>

  <?php if (!$rows): ?>
    <div class="empty mt">
      <?= icon('user', 32) ?>
      <h3>No referrers match</h3>
      <p>Every customer gets a code the first time they register, so this fills as the app is used.</p>
    </div>
  <?php else: ?>
    <div class="table-wrap mt">
      <table class="data">
        <thead><tr>
          <th>Code</th><th>Name</th><th>Number</th><th class="right">Referrals</th>
          <th class="right">Balance</th><th class="right">Earned</th><th class="right">Paid</th>
          <th>Status</th><th>Verified</th><th></th>
        </tr></thead>
        <tbody>
          <?php foreach ($rows as $r): ?>
            <tr>
              <td class="mono"><strong><?= e($r['code']) ?></strong></td>
              <td><?= e($r['name'] !== '' ? $r['name'] : '—') ?></td>
              <td class="mono nowrap small"><?= e(CustomerRepository::displayNumber((string) $r['msisdn'])) ?></td>
              <td class="right"><?= number_format((int) $r['referrals_count']) ?></td>
              <td class="right"><strong><?= e($ksh((int) $r['balance_cents'])) ?></strong></td>
              <td class="right muted small"><?= e($ksh((int) $r['lifetime_earned_cents'])) ?></td>
              <td class="right muted small"><?= e($ksh((int) $r['lifetime_paid_cents'])) ?></td>
              <td><?= $badge((string) $r['status']) ?></td>
              <td class="small muted"><?= $r['verified_msisdn'] ? 'Yes' : 'No' ?></td>
              <td><a class="btn btn--ghost btn--sm" href="<?= e(url('/referrals/referrers/' . (int) $r['id'])) ?>">
                <?= icon('eye', 16) ?> Open</a></td>
            </tr>
          <?php endforeach; ?>
        </tbody>
      </table>
    </div>

    <?php if ($pages > 1): ?>
      <div class="row between mt">
        <span class="muted small">Page <?= (int) $page ?> of <?= (int) $pages ?></span>
        <div class="row" style="gap:8px">
          <?php if ($page > 1): ?>
            <a class="btn btn--ghost btn--sm" href="<?= e($link(['page' => $page - 1])) ?>">Previous</a>
          <?php endif; ?>
          <?php if ($page < $pages): ?>
            <a class="btn btn--ghost btn--sm" href="<?= e($link(['page' => $page + 1])) ?>">Next</a>
          <?php endif; ?>
        </div>
      </div>
    <?php endif; ?>
  <?php endif; ?>
</div>
