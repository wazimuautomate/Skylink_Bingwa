<?php
/**
 * One referrer: their ledger, their referees, and the two manual controls.
 *
 * The ledger is the whole story — every shilling in and out, in order, with the
 * idempotency key that made it exactly-once. A manual adjustment appends to it
 * like everything else; nothing on this page edits a balance directly.
 */
use App\Repositories\CustomerRepository;
use App\Repositories\PaymentRepository;

$ksh = static fn(int $cents): string => 'Ksh ' . number_format($cents / 100, 2);

$typeLabel = [
    'SIGNUP_BONUS'    => 'Signup bonus',
    'EARN'            => 'Commission',
    'REVERSAL'        => 'Reversal',
    'WITHDRAW_HOLD'   => 'Withdrawal held',
    'WITHDRAW_SETTLE' => 'Withdrawal settled',
    'WITHDRAW_REFUND' => 'Withdrawal refunded',
    'ADJUST'          => 'Manual adjustment',
];
$frozen = $referrer['payout_frozen_until'] !== null && $referrer['payout_frozen_until'] > gmdate('Y-m-d H:i:s');
$driftCents = (int) $referrer['balance_cents'] - (int) $balances['total'];
?>

<div class="page-head">
  <div>
    <h1><?= e($referrer['name'] !== '' ? $referrer['name'] : 'Referrer') ?>
      <span class="mono muted">· <?= e($referrer['code']) ?></span></h1>
    <div class="sub">
      <?= e(CustomerRepository::displayNumber((string) $referrer['msisdn'])) ?> ·
      <?= e($referrer['status']) ?><?= $referrer['status_reason'] !== '' ? ' — ' . e($referrer['status_reason']) : '' ?>
    </div>
  </div>
  <div class="page-head__actions">
    <a class="btn btn--ghost" href="<?= e(url('/referrals/referrers')) ?>"><?= icon('chevron', 18) ?> All referrers</a>
  </div>
</div>

<?php if ($driftCents !== 0): ?>
  <div class="card mb" style="border-left:4px solid var(--error, #BA1A1A)">
    <strong><?= icon('warning', 18) ?> This referrer's cached balance disagrees with their ledger by
      <?= e($ksh($driftCents)) ?>.</strong>
    <div class="small muted mt">Do not pay out until this is explained. The ledger is the truth; the cache is not.</div>
  </div>
<?php endif; ?>

<div class="grid cards mb">
  <div class="card">
    <div class="stat">
      <div class="stat__label">Withdrawable now</div>
      <div class="stat__value"><?= e($ksh((int) $balances['available'])) ?></div>
      <div class="small muted">Matured, and past the bonus purchase gate</div>
    </div>
  </div>
  <div class="card">
    <div class="stat">
      <div class="stat__label">Held / not yet matured</div>
      <div class="stat__value"><?= e($ksh((int) $balances['pending'])) ?></div>
      <div class="small muted"><?= (int) $settings['referral_hold_hours'] ?>h hold, plus any in-flight payout</div>
    </div>
  </div>
  <div class="card">
    <div class="stat">
      <div class="stat__label">Total balance (ledger)</div>
      <div class="stat__value"><?= e($ksh((int) $balances['total'])) ?></div>
      <div class="small muted">Lifetime earned <?= e($ksh((int) $referrer['lifetime_earned_cents'])) ?></div>
    </div>
  </div>
  <div class="card">
    <div class="stat">
      <div class="stat__label">Payout number</div>
      <div class="stat__value" style="font-size:1.1rem">
        <?= $referrer['verified_msisdn']
              ? e(CustomerRepository::displayNumber((string) $referrer['verified_msisdn']))
              : 'Not verified' ?>
      </div>
      <div class="small muted">
        <?php if ($frozen): ?>
          Frozen until <?= e(PaymentRepository::nairobiTime($referrer['payout_frozen_until'])) ?>
        <?php elseif ($referrer['verified_msisdn']): ?>
          Verified <?= e(PaymentRepository::nairobiTime($referrer['verified_at'])) ?>
        <?php else: ?>
          They verify by SMS code the first time they withdraw
        <?php endif; ?>
      </div>
    </div>
  </div>
</div>

<?php if (can('referrals.manage')): ?>
  <div class="grid cards mb">
    <div class="card">
      <h3><?= icon('shield', 18) ?> Account status</h3>
      <div class="small muted">
        <strong>Earning blocked</strong> stops new commission but keeps the balance.
        <strong>Payout held</strong> keeps earning but blocks withdrawal — the right setting while investigating.
        <strong>Banned</strong> stops both and refuses new referrals.
      </div>
      <form method="post" action="<?= e(url('/referrals/referrers/' . (int) $referrer['id'] . '/status')) ?>" class="mt">
        <?= App\Core\Csrf::field() ?>
        <div class="field">
          <label for="st">Status</label>
          <select id="st" name="status">
            <?php foreach (['ACTIVE' => 'Active', 'EARN_BLOCKED' => 'Earning blocked', 'PAYOUT_BLOCKED' => 'Payout held', 'BANNED' => 'Banned'] as $k => $v): ?>
              <option value="<?= e($k) ?>" <?= $referrer['status'] === $k ? 'selected' : '' ?>><?= e($v) ?></option>
            <?php endforeach; ?>
          </select>
        </div>
        <div class="field">
          <label for="st-reason">Reason</label>
          <input id="st-reason" type="text" name="reason" maxlength="180"
                 value="<?= e($referrer['status_reason']) ?>" placeholder="What you found">
        </div>
        <button class="btn" type="submit"><?= icon('check', 16) ?> Apply</button>
      </form>
    </div>

    <div class="card">
      <h3><?= icon('money', 18) ?> Manual adjustment</h3>
      <div class="small muted">
        Positive credits them, negative takes it back. This writes a ledger entry — it never edits a balance —
        so the history stays complete. A reason is required.
      </div>
      <form method="post" action="<?= e(url('/referrals/referrers/' . (int) $referrer['id'] . '/adjust')) ?>" class="mt"
            data-confirm="Post this adjustment to the referrer's ledger?" data-confirm-title="Adjust balance">
        <?= App\Core\Csrf::field() ?>
        <div class="field">
          <label for="adj">Amount (Ksh, may be negative)</label>
          <input id="adj" type="number" step="0.01" name="amount" placeholder="e.g. 50 or -50" required>
        </div>
        <div class="field">
          <label for="adj-note">Reason</label>
          <input id="adj-note" type="text" name="note" maxlength="180" required
                 placeholder="e.g. goodwill after failed payout SKB-42">
        </div>
        <button class="btn" type="submit"><?= icon('plus', 16) ?> Post adjustment</button>
      </form>
    </div>
  </div>
<?php endif; ?>

<div class="card mb">
  <h3><?= icon('user', 18) ?> People they referred (<?= count($referees) ?>)</h3>
  <?php if (!$referees): ?>
    <div class="small muted mt">Nobody has used this code yet.</div>
  <?php else: ?>
    <div class="table-wrap mt">
      <table class="data">
        <thead><tr><th>Name</th><th>Number</th><th>Joined</th><th>First purchase</th>
          <th class="right">Purchases</th><th class="right">Earned</th></tr></thead>
        <tbody>
          <?php foreach ($referees as $rf): ?>
            <tr>
              <td><?= e($rf['name'] !== '' ? $rf['name'] : '—') ?></td>
              <td class="mono small nowrap"><?= e(CustomerRepository::displayNumber((string) $rf['msisdn'])) ?></td>
              <td class="muted small nowrap"><?= e(PaymentRepository::nairobiTime($rf['attributed_at'])) ?></td>
              <td class="small nowrap">
                <?= $rf['first_purchase_at']
                      ? e(PaymentRepository::nairobiTime($rf['first_purchase_at']))
                      : '<span class="tag amber">Bonus locked</span>' ?>
              </td>
              <td class="right"><?= (int) $rf['purchases_count'] ?></td>
              <td class="right"><?= e($ksh((int) $rf['earned_cents'])) ?></td>
            </tr>
          <?php endforeach; ?>
        </tbody>
      </table>
    </div>
  <?php endif; ?>
</div>

<div class="card">
  <h3><?= icon('audit', 18) ?> Ledger</h3>
  <div class="small muted">
    Append-only. Every row carries the idempotency key that made it exactly-once, so a replayed
    M-Pesa callback can never have credited twice.
  </div>
  <?php if (!$ledger): ?>
    <div class="small muted mt">No entries yet.</div>
  <?php else: ?>
    <div class="table-wrap mt">
      <table class="data">
        <thead><tr><th>When</th><th>Type</th><th class="right">Amount</th><th>Matures</th>
          <th>Note</th><th>By</th><th>Key</th></tr></thead>
        <tbody>
          <?php foreach ($ledger as $l): ?>
            <?php $amt = (int) $l['amount_cents']; ?>
            <tr>
              <td class="muted small nowrap"><?= e(PaymentRepository::nairobiTime($l['created_at'])) ?></td>
              <td class="small"><?= e($typeLabel[$l['entry_type']] ?? $l['entry_type']) ?></td>
              <td class="right nowrap" style="<?= $amt < 0 ? 'color:var(--error,#BA1A1A)' : ($amt > 0 ? 'color:var(--success,#157A3B)' : '') ?>">
                <?= $amt > 0 ? '+' : '' ?><?= e($ksh($amt)) ?>
              </td>
              <td class="muted small nowrap">
                <?= $l['matures_at'] > gmdate('Y-m-d H:i:s')
                      ? e(PaymentRepository::nairobiTime($l['matures_at']))
                      : '—' ?>
              </td>
              <td class="small muted"><?= e($l['note']) ?></td>
              <td class="small muted"><?= e($l['created_by']) ?></td>
              <td class="mono small muted"><?= e($l['idempotency_key']) ?></td>
            </tr>
          <?php endforeach; ?>
        </tbody>
      </table>
    </div>
  <?php endif; ?>
</div>
