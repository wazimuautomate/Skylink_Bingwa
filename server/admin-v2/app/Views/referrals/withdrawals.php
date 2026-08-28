<?php
/**
 * The withdrawals queue.
 *
 * UNKNOWN sorts first, deliberately. It is the only status that needs a person:
 * M-Pesa has not told us whether the money moved, and the system will never guess
 * — refunding a payout that actually paid would pay the customer twice out of the
 * float, with no way to get it back.
 */
use App\Repositories\CustomerRepository;
use App\Repositories\PaymentRepository;

$ksh = static fn(int $cents): string => 'Ksh ' . number_format($cents / 100, 2);
$queryState = array_filter($filters, static fn($v): bool => (string) $v !== '');
$link = static function (array $overrides = []) use ($queryState): string {
    $q = array_filter(array_merge($queryState, $overrides), static fn($v): bool => (string) $v !== '');
    return url('/referrals/withdrawals' . ($q ? '?' . http_build_query($q) : ''));
};

$statusMeta = [
    'REQUESTED'  => ['Queued', 'muted', 'Waiting for the next submit run.'],
    'SUBMITTING' => ['Submitting', 'amber', 'Mid-flight. The reconciler will resolve it if this sticks.'],
    'SUBMITTED'  => ['Sent to M-Pesa', 'amber', 'Waiting for the result callback.'],
    'PAID'       => ['Paid', 'green', ''],
    'FAILED'     => ['Failed', 'red', 'The money was returned to the referrer\'s balance.'],
    'UNKNOWN'    => ['Unresolved', 'red', 'M-Pesa has not confirmed either way. Never auto-refunded.'],
    'CANCELLED'  => ['Cancelled', 'muted', ''],
];
?>

<div class="page-head">
  <div>
    <h1>Withdrawals</h1>
    <div class="sub"><?= number_format((int) $total) ?> payout request(s).</div>
  </div>
  <div class="page-head__actions">
    <a class="btn btn--ghost" href="<?= e(url('/referrals')) ?>"><?= icon('chevron', 18) ?> Overview</a>
  </div>
</div>

<?php if ((int) $settings['referral_payouts_enabled'] !== 1): ?>
  <div class="card mb" style="border-left:4px solid var(--warning, #9A5A00)">
    <strong><?= icon('warning', 18) ?> Automatic payouts are off.</strong>
    <div class="small muted mt">Requests are refused at the app rather than queued. Turn payouts on in
      <a href="<?= e(url('/referrals')) ?>">programme settings</a> once B2C is live.</div>
  </div>
<?php endif; ?>

<div class="card">
  <form class="pay-filters" method="get" action="<?= e(url('/referrals/withdrawals')) ?>">
    <div class="field pay-search">
      <label for="w-q">Search</label>
      <input id="w-q" type="search" name="q" value="<?= e($filters['q']) ?>" placeholder="Name, number or M-Pesa receipt">
    </div>
    <div class="field">
      <label for="w-status">Status</label>
      <select id="w-status" name="status">
        <option value="">Any</option>
        <?php foreach ($statusMeta as $k => $m): ?>
          <option value="<?= e($k) ?>" <?= $filters['status'] === $k ? 'selected' : '' ?>><?= e($m[0]) ?></option>
        <?php endforeach; ?>
      </select>
    </div>
    <div class="field">
      <label>&nbsp;</label>
      <div class="row" style="gap:8px">
        <button class="btn" type="submit"><?= icon('search', 16) ?> Filter</button>
        <?php if ($queryState): ?>
          <a class="btn btn--ghost" href="<?= e(url('/referrals/withdrawals')) ?>">Clear</a>
        <?php endif; ?>
      </div>
    </div>
  </form>

  <?php if (!$rows): ?>
    <div class="empty mt">
      <?= icon('money', 32) ?>
      <h3>No withdrawals</h3>
      <p>Referrers can withdraw once their balance passes
        <?= e($ksh((int) $settings['referral_min_withdraw_cents'])) ?> and they have verified their number.</p>
    </div>
  <?php else: ?>
    <div class="table-wrap mt">
      <table class="data">
        <thead><tr>
          <th>#</th><th>Referrer</th><th>To number</th><th class="right">Amount</th>
          <th>Status</th><th>Receipt</th><th>Requested</th><th>Reference</th><th></th>
        </tr></thead>
        <tbody>
          <?php foreach ($rows as $w): ?>
            <?php
              $meta = $statusMeta[$w['status']] ?? [$w['status'], 'muted', ''];
              $needsHuman = in_array($w['status'], ['UNKNOWN', 'SUBMITTING'], true);
            ?>
            <tr>
              <td class="mono small"><?= (int) $w['id'] ?></td>
              <td>
                <?= e($w['name'] !== '' ? $w['name'] : '—') ?>
                <div class="mono small muted"><?= e($w['code']) ?></div>
              </td>
              <td class="mono small nowrap"><?= e(CustomerRepository::displayNumber((string) $w['msisdn'])) ?></td>
              <td class="right"><strong><?= e($ksh((int) $w['amount_cents'])) ?></strong></td>
              <td>
                <span class="tag <?= e($meta[1]) ?>"><?= e($meta[0]) ?></span>
                <?php if ($meta[2] !== ''): ?><div class="small muted"><?= e($meta[2]) ?></div><?php endif; ?>
                <?php if (($w['result_desc'] ?? '') !== ''): ?>
                  <div class="small muted"><?= e(mb_substr((string) $w['result_desc'], 0, 90)) ?></div>
                <?php endif; ?>
              </td>
              <td class="mono small"><?= e($w['mpesa_receipt'] ?: '—') ?></td>
              <td class="muted small nowrap"><?= e(PaymentRepository::nairobiTime($w['requested_at'])) ?></td>
              <td class="mono small muted"><?= e(mb_substr((string) $w['originator_conversation_id'], 0, 22)) ?></td>
              <td>
                <?php if ($needsHuman && can('referrals.manage')): ?>
                  <details>
                    <summary class="btn btn--ghost btn--sm">Resolve</summary>
                    <form method="post"
                          action="<?= e(url('/referrals/withdrawals/' . (int) $w['id'] . '/resolve')) ?>"
                          class="mt"
                          data-confirm="Only do this after checking the M-Pesa statement for this exact payout. Marking it wrong either pays twice or refuses money that was already sent."
                          data-confirm-title="Close this payout by hand">
                      <?= App\Core\Csrf::field() ?>
                      <div class="field">
                        <label for="o<?= (int) $w['id'] ?>">What M-Pesa actually did</label>
                        <select id="o<?= (int) $w['id'] ?>" name="outcome">
                          <option value="PAID">It paid — settle the hold</option>
                          <option value="FAILED">It did not pay — return the money</option>
                        </select>
                      </div>
                      <div class="field">
                        <label for="n<?= (int) $w['id'] ?>">What you checked</label>
                        <input id="n<?= (int) $w['id'] ?>" type="text" name="note" maxlength="180" required
                               placeholder="e.g. M-Pesa statement shows receipt SGH4X2K9QP">
                      </div>
                      <button class="btn btn--sm" type="submit">Close payout</button>
                    </form>
                  </details>
                <?php endif; ?>
              </td>
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
