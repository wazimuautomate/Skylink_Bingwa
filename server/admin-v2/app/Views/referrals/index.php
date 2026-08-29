<?php
/**
 * Referrals overview — the numbers that decide whether this programme is safe.
 *
 * Outstanding liability and unresolved payouts are the two that need a permanent
 * eye: liability grows quietly with every sale, and an unresolved payout is never
 * auto-refunded (refunding one that actually paid would pay the customer twice).
 *
 * No <script> anywhere — the CSP is script-src 'self'.
 */
use App\Repositories\PaymentRepository;

$ksh = static fn(int $cents): string => 'Ksh ' . number_format($cents / 100, 2);
// Plain numbers for form inputs: strips ".00" so a whole-shilling value looks
// like one (200, not 200.00) but a genuine fraction (e.g. a % rate) still shows.
$num = static fn(float $n): string => rtrim(rtrim(number_format($n, 2, '.', ''), '0'), '.');
$payoutsOn = (int) $settings['referral_payouts_enabled'] === 1;
$programmeOn = (int) $settings['referral_enabled'] === 1;
?>

<div class="page-head">
  <div>
    <h1>Referrals &amp; commissions</h1>
    <div class="sub">Customers share a code, earn on what their friends buy, and withdraw to M-Pesa.</div>
  </div>
  <div class="page-head__actions">
    <div class="seg">
      <a class="is-active" href="<?= e(url('/referrals')) ?>">Overview</a>
      <a href="<?= e(url('/referrals/referrers')) ?>">Referrers</a>
      <a href="<?= e(url('/referrals/withdrawals')) ?>">Withdrawals</a>
    </div>
  </div>
</div>

<?php if (!$payoutsOn): ?>
  <div class="alert warning mb">
    <?= icon('warning', 18) ?>
    <div><b>Automatic payouts are off.</b> Commission still builds up and withdrawal requests still queue safely — nothing reaches M-Pesa until this is switched on below.</div>
  </div>
<?php endif; ?>

<?php if ((int) $settings['referral_commission_bps'] === 0): ?>
  <div class="alert warning mb">
    <?= icon('warning', 18) ?>
    <div><b>Commission rate is 0%.</b> Referrers earn the signup bonus only. Set a rate below — it can never be saved above an offer's own margin.</div>
  </div>
<?php endif; ?>

<?php if (!empty($drift)): ?>
  <div class="alert error mb">
    <?= icon('warning', 18) ?>
    <div>
      <b>Ledger drift on <?= count($drift) ?> referrer(s).</b> A cached balance disagrees with its ledger total — not auto-corrected, since that would erase the evidence.
      <div class="table-wrap mt">
        <table class="data">
          <thead><tr><th>Code</th><th class="right">Cached</th><th class="right">Ledger</th><th class="right">Difference</th></tr></thead>
          <tbody>
            <?php foreach ($drift as $d): ?>
              <tr>
                <td class="mono"><?= e($d['code']) ?></td>
                <td class="right"><?= e($ksh((int) $d['balance_cents'])) ?></td>
                <td class="right"><?= e($ksh((int) $d['ledger_total'])) ?></td>
                <td class="right"><?= e($ksh((int) $d['balance_cents'] - (int) $d['ledger_total'])) ?></td>
              </tr>
            <?php endforeach; ?>
          </tbody>
        </table>
      </div>
    </div>
  </div>
<?php endif; ?>

<div class="grid cards mb">
  <div class="card">
    <div class="between">
      <div class="stat">
        <div class="stat__label">Outstanding liability</div>
        <div class="stat__value"><?= e($ksh((int) $summary['liability_cents'])) ?></div>
        <div class="small muted">Owed, not yet paid</div>
      </div>
      <div class="stat__icon <?= (int) $summary['liability_cents'] > 0 ? 'blue' : 'green' ?>"><?= icon('money', 20) ?></div>
    </div>
  </div>
  <div class="card">
    <div class="between">
      <div class="stat">
        <div class="stat__label">Not yet withdrawable</div>
        <div class="stat__value"><?= e($ksh((int) $summary['unmatured_cents'])) ?></div>
        <div class="small muted">Inside the <?= (int) $settings['referral_hold_hours'] ?>h hold window</div>
      </div>
      <div class="stat__icon blue"><?= icon('clock', 20) ?></div>
    </div>
  </div>
  <div class="card">
    <div class="between">
      <div class="stat">
        <div class="stat__label">Paid out all time</div>
        <div class="stat__value"><?= e($ksh((int) $summary['paid_cents'])) ?></div>
        <div class="small muted"><?= e($ksh((int) $summary['earned_cents'])) ?> earned in total</div>
      </div>
      <div class="stat__icon green"><?= icon('check', 20) ?></div>
    </div>
  </div>
  <div class="card">
    <div class="between">
      <div class="stat">
        <div class="stat__label">Referrers</div>
        <div class="stat__value"><?= number_format((int) $summary['referrers']) ?></div>
        <div class="small muted"><?= number_format((int) $summary['restricted']) ?> restricted or banned</div>
      </div>
      <div class="stat__icon blue"><?= icon('user', 20) ?></div>
    </div>
  </div>
  <div class="card">
    <div class="between">
      <div class="stat">
        <div class="stat__label">Referrals made</div>
        <div class="stat__value"><?= number_format((int) $summary['referrals_total']) ?></div>
        <div class="small muted"><?= number_format((int) $summary['referrals_conv']) ?> converted to a purchase</div>
      </div>
      <div class="stat__icon blue"><?= icon('layers', 20) ?></div>
    </div>
  </div>
  <div class="card">
    <div class="between">
      <div class="stat">
        <div class="stat__label">Payouts in flight</div>
        <div class="stat__value"><?= number_format((int) $summary['inflight_count']) ?></div>
        <div class="small muted"><?= e($ksh((int) $summary['inflight_cents'])) ?> committed</div>
      </div>
      <div class="stat__icon <?= (int) $summary['stuck_count'] > 0 ? 'red' : 'blue' ?>"><?= icon('sync', 20) ?></div>
    </div>
  </div>
</div>

<?php if ((int) $summary['stuck_count'] > 0): ?>
  <div class="alert error mb">
    <?= icon('warning', 18) ?>
    <div><b><?= (int) $summary['stuck_count'] ?> payout(s) unresolved for over an hour.</b> M-Pesa has not confirmed either way — never auto-refunded. <a href="<?= e(url('/referrals/withdrawals?status=UNKNOWN')) ?>">Open the queue</a>.</div>
  </div>
<?php endif; ?>

<div class="grid cards mb">
  <div class="card">
    <h3><?= icon('bell', 18) ?> Notification queue</h3>
    <div class="small muted">SMS and push are queued and drained by a cron every minute.</div>
    <div class="row mt" style="gap:20px">
      <div><strong><?= number_format((int) ($outbox['pending'] ?? 0)) ?></strong> <span class="muted small">waiting</span></div>
      <div><strong><?= number_format((int) ($outbox['sent'] ?? 0)) ?></strong> <span class="muted small">sent</span></div>
      <div><strong><?= number_format((int) ($outbox['dead'] ?? 0)) ?></strong> <span class="muted small">gave up</span></div>
    </div>
    <?php if ((int) ($outbox['pending'] ?? 0) > 200): ?>
      <div class="small mt" style="color:var(--warning,#9A5A00)">Large backlog — check the <span class="mono">cron_referrals.php outbox</span> cron is running.</div>
    <?php endif; ?>
  </div>

  <div class="card">
    <h3><?= icon('shield', 18) ?> Blocked / suspicious devices</h3>
    <div class="small muted">One handset may redeem a code once, ever — this is what stops the SIM-swap loop.</div>
    <?php if (!$devices): ?>
      <div class="small muted mt">Nothing flagged.</div>
    <?php else: ?>
      <div class="table-wrap mt">
        <table class="data">
          <thead><tr><th>Device</th><th class="right">Numbers</th><th>Redeemed</th><th>Reason</th></tr></thead>
          <tbody>
            <?php foreach ($devices as $d): ?>
              <tr>
                <td class="mono small"><?= e(substr((string) $d['device_hash'], 0, 12)) ?>&hellip;</td>
                <td class="right"><?= (int) $d['msisdn_count'] ?></td>
                <td class="mono small"><?= e($d['redeemed_code'] !== '' ? $d['redeemed_code'] : '—') ?></td>
                <td class="small muted"><?= e($d['block_reason'] !== '' ? $d['block_reason'] : ((int) $d['blocked'] ? 'Blocked' : 'Watch')) ?></td>
              </tr>
            <?php endforeach; ?>
          </tbody>
        </table>
      </div>
    <?php endif; ?>
  </div>
</div>

<div class="card mb">
  <div class="card__head">
    <h2>Programme settings</h2>
    <span class="sub">Real money — every change is written to the audit log.</span>
  </div>

  <form method="post" action="<?= e(url('/referrals/settings')) ?>">
    <?= App\Core\Csrf::field() ?>

    <div class="stack" style="gap:12px;text-align:left;max-width:640px;margin:0 auto 22px">
      <label class="switch">
        <input type="checkbox" name="referral_enabled" value="1" <?= $programmeOn ? 'checked' : '' ?>>
        <span class="track"></span>
        <span>
          <b>Referral programme on</b>
          <div class="small muted">Off stops new attributions and accrual. Existing balances stay untouched.</div>
        </span>
      </label>
      <label class="switch">
        <input type="checkbox" name="referral_payouts_enabled" value="1" <?= $payoutsOn ? 'checked' : '' ?>>
        <span class="track"></span>
        <span>
          <b>Automatic M-Pesa payouts on</b>
          <div class="small muted">The kill switch. Off refuses withdrawal requests cleanly — no money can leave.</div>
        </span>
      </label>
      <label class="switch">
        <input type="checkbox" name="referral_bonus_requires_purchase" value="1"
               <?= (int) $settings['referral_bonus_requires_purchase'] === 1 ? 'checked' : '' ?>>
        <span class="track"></span>
        <span>
          <b>Signup bonus only unlocks after the friend buys</b>
          <div class="small muted">Strongly recommended — this is what makes SIM-farming it unprofitable.</div>
        </span>
      </label>
    </div>

    <h3>Earning</h3>
    <div class="form-grid mb">
      <div class="field">
        <label for="s-pct">Commission rate (%)</label>
        <input id="s-pct" type="number" name="referral_commission_pct" min="0" max="100" step="0.01"
               value="<?= e($num($settings['referral_commission_bps'] / 100)) ?>">
        <div class="hint">E.g. 10% earns Ksh 10 when a friend spends Ksh 100. Capped at each offer's own margin.</div>
      </div>
      <div class="field">
        <label for="s-bonus">Signup bonus (Ksh)</label>
        <input id="s-bonus" type="number" name="referral_signup_bonus_ksh" min="0" step="1"
               value="<?= e($num($settings['referral_signup_bonus_cents'] / 100)) ?>">
        <div class="hint">Paid once per person who joins with a code.</div>
      </div>
      <div class="field">
        <label for="s-hold">Hold window (hours)</label>
        <input id="s-hold" type="number" name="referral_hold_hours" min="0" max="720"
               value="<?= (int) $settings['referral_hold_hours'] ?>">
        <div class="hint">Earnings can't be withdrawn until this passes.</div>
      </div>
    </div>

    <h3>Withdrawals</h3>
    <div class="form-grid mb">
      <div class="field">
        <label for="s-min">Minimum withdrawal (Ksh)</label>
        <input id="s-min" type="number" name="referral_min_withdraw_ksh" min="1" step="1"
               value="<?= e($num($settings['referral_min_withdraw_cents'] / 100)) ?>">
      </div>
      <div class="field">
        <label for="s-max">Maximum per withdrawal (Ksh)</label>
        <input id="s-max" type="number" name="referral_max_withdraw_ksh" min="1" step="1"
               value="<?= e($num($settings['referral_max_withdraw_cents'] / 100)) ?>">
      </div>
      <div class="field">
        <label for="s-cool">Cooldown between payouts (hours)</label>
        <input id="s-cool" type="number" name="referral_cooldown_hours" min="0" max="720"
               value="<?= (int) $settings['referral_cooldown_hours'] ?>">
        <div class="hint">Limits how often one referrer can cash out.</div>
      </div>
      <div class="field">
        <label for="s-cap">Daily payout cap, business-wide (Ksh)</label>
        <input id="s-cap" type="number" name="referral_daily_cap_ksh" min="0" step="1"
               value="<?= e($num($settings['referral_daily_cap_cents'] / 100)) ?>">
        <div class="hint">The circuit breaker, whatever else goes wrong.</div>
      </div>
      <div class="field">
        <label for="s-floor">Float alert floor (Ksh)</label>
        <input id="s-floor" type="number" name="referral_float_floor_ksh" min="0" step="1"
               value="<?= e($num($settings['referral_float_floor_cents'] / 100)) ?>">
        <div class="hint">Warns when the B2C account drops below this.</div>
      </div>
    </div>

    <h3>B2C payout credential</h3>
    <div class="form-grid mb">
      <div class="field">
        <label for="s-b2c-pass">
          New initiator password
          <span class="status <?= $b2cPasswordSet ? 'active' : 'requested' ?>" style="margin-left:8px">
            <?= $b2cPasswordSet ? 'Set' : 'Not set' ?>
          </span>
        </label>
        <input id="s-b2c-pass" type="password" name="b2c_initiator_password" autocomplete="new-password"
               placeholder="<?= $b2cPasswordSet ? '••••••••' : 'Not set yet' ?>">
        <div class="hint">
          Leave blank to keep it unchanged. Safaricom expires this every few weeks —
          paste the new one here when it does; the next payout run picks it up automatically, no redeploy needed.
        </div>
      </div>
    </div>

    <h3>Anti-fraud</h3>
    <div class="form-grid">
      <div class="field">
        <label for="s-dev">Maximum numbers per handset</label>
        <input id="s-dev" type="number" name="referral_max_device_msisdns" min="1" max="20"
               value="<?= (int) $settings['referral_max_device_msisdns'] ?>">
        <div class="hint">A shared phone reaches 2–3; a farm keeps climbing.</div>
      </div>
      <div class="field">
        <label for="s-vel">Maximum referrals per referrer per day</label>
        <input id="s-vel" type="number" name="referral_max_daily_referrals" min="1" max="500"
               value="<?= (int) $settings['referral_max_daily_referrals'] ?>">
      </div>
      <div class="field">
        <label for="s-earn">Maximum earned per referrer per day (Ksh)</label>
        <input id="s-earn" type="number" name="referral_max_daily_earn_ksh" min="0" step="1"
               value="<?= e($num($settings['referral_max_daily_earn_cents'] / 100)) ?>">
        <div class="hint">Past this the account is parked for review.</div>
      </div>
      <div class="field">
        <label for="s-sms">Maximum "someone joined" SMS per referrer per day</label>
        <input id="s-sms" type="number" name="referral_join_sms_daily_cap" min="0" max="200"
               value="<?= (int) $settings['referral_join_sms_daily_cap'] ?>">
        <div class="hint">Stops code-guessing from burning SMS credit.</div>
      </div>
    </div>

    <div class="row mt" style="justify-content:center">
      <button class="btn" type="submit"><?= icon('check', 16) ?> Save settings</button>
    </div>
  </form>
</div>

<div class="card">
  <div class="card__head">
    <h3><?= icon('audit', 18) ?> Recent money events</h3>
    <span class="sub">Every attribution, accrual, payout and rejection, newest first.</span>
  </div>
  <?php if (!$events): ?>
    <div class="small muted mt">Nothing yet.</div>
  <?php else: ?>
    <div class="table-wrap mt">
      <table class="data">
        <thead><tr><th>When</th><th>Event</th><th>Detail</th></tr></thead>
        <tbody>
          <?php foreach ($events as $ev): ?>
            <tr>
              <td class="muted small nowrap"><?= e(PaymentRepository::nairobiTime($ev['created_at'] ?? null)) ?></td>
              <td class="mono small"><?= e($ev['event']) ?></td>
              <td class="small muted"><?= e(mb_substr((string) $ev['detail'], 0, 160)) ?></td>
            </tr>
          <?php endforeach; ?>
        </tbody>
      </table>
    </div>
  <?php endif; ?>
</div>
