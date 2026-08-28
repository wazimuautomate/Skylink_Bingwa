<?php
/**
 * Referrals overview — the numbers that decide whether this programme is safe.
 *
 * Two of these deserve a permanent eye:
 *
 *   Outstanding liability   money owed to referrers that has not been paid. It
 *                           grows quietly with every sale and is invisible unless
 *                           something puts it on a screen.
 *   Unresolved payouts      a withdrawal nobody knows the fate of. Never auto-
 *                           refunded, because refunding one that actually paid
 *                           pays the customer twice out of the float.
 *
 * No <script> anywhere — the CSP is script-src 'self'.
 */
use App\Repositories\PaymentRepository;

$ksh = static fn(int $cents): string => 'Ksh ' . number_format($cents / 100, 2);
$pct = static fn(int $bps): string => number_format($bps / 100, 2) . '%';
$payoutsOn = (int) $settings['referral_payouts_enabled'] === 1;
$programmeOn = (int) $settings['referral_enabled'] === 1;
?>

<div class="page-head">
  <div>
    <h1>Referrals &amp; commissions</h1>
    <div class="sub">Customers share a code, earn on what their friends buy, and withdraw to M-Pesa.</div>
  </div>
  <div class="page-head__actions">
    <a class="btn btn--ghost" href="<?= e(url('/referrals/referrers')) ?>"><?= icon('user', 18) ?> Referrers</a>
    <a class="btn btn--ghost" href="<?= e(url('/referrals/withdrawals')) ?>"><?= icon('money', 18) ?> Withdrawals</a>
  </div>
</div>

<?php if (!$payoutsOn): ?>
  <div class="card mb" style="border-left:4px solid var(--warning, #9A5A00)">
    <strong><?= icon('warning', 18) ?> Automatic payouts are OFF.</strong>
    <div class="small muted mt">
      Customers can still earn commission and request a withdrawal, and those requests queue safely.
      Nothing is sent to M-Pesa until you turn payouts on below. Leave this off until B2C is live and tested.
    </div>
  </div>
<?php endif; ?>

<?php if ((int) $settings['referral_commission_bps'] === 0): ?>
  <div class="card mb" style="border-left:4px solid var(--warning, #9A5A00)">
    <strong><?= icon('warning', 18) ?> The commission rate is 0%.</strong>
    <div class="small muted mt">
      Referrers earn the signup bonus but nothing on purchases. Set a rate below — and set each offer's
      real margin on the offer form first, so the rate can never be saved above what the offer actually earns.
    </div>
  </div>
<?php endif; ?>

<?php if (!empty($drift)): ?>
  <div class="card mb" style="border-left:4px solid var(--error, #BA1A1A)">
    <strong><?= icon('warning', 18) ?> Ledger drift detected on <?= count($drift) ?> referrer(s).</strong>
    <div class="small muted mt">
      A cached balance disagrees with the sum of its ledger entries, which means something wrote outside the
      ledger. It is <em>not</em> auto-corrected — that would destroy the evidence. Investigate before paying out.
    </div>
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
<?php endif; ?>

<div class="grid cards mb">
  <div class="card">
    <div class="between">
      <div class="stat">
        <div class="stat__label">Outstanding liability</div>
        <div class="stat__value"><?= e($ksh((int) $summary['liability_cents'])) ?></div>
        <div class="small muted">Owed to referrers, not yet paid</div>
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
        <div class="small muted"><?= number_format((int) $summary['earned_cents'] / 100, 2) ?> earned in total</div>
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
        <div class="small muted"><?= number_format((int) $summary['referrals_conv']) ?> have bought at least once</div>
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
  <div class="card mb" style="border-left:4px solid var(--error, #BA1A1A)">
    <strong><?= icon('warning', 18) ?> <?= (int) $summary['stuck_count'] ?> payout(s) unresolved for over an hour.</strong>
    <div class="small muted mt">
      M-Pesa has not told us whether these went through. They are never auto-refunded, because releasing the
      hold on a payout that actually paid would pay the customer twice.
      <a href="<?= e(url('/referrals/withdrawals?status=UNKNOWN')) ?>">Open the queue</a> and resolve each one.
    </div>
  </div>
<?php endif; ?>

<div class="grid cards mb">
  <div class="card">
    <h3><?= icon('bell', 18) ?> Notification queue</h3>
    <div class="small muted">
      SMS and push are queued, never sent inline from a payment callback. A cron drains this every minute.
    </div>
    <div class="row mt" style="gap:20px">
      <div><strong><?= number_format((int) ($outbox['pending'] ?? 0)) ?></strong> <span class="muted small">waiting</span></div>
      <div><strong><?= number_format((int) ($outbox['sent'] ?? 0)) ?></strong> <span class="muted small">sent</span></div>
      <div><strong><?= number_format((int) ($outbox['dead'] ?? 0)) ?></strong> <span class="muted small">gave up</span></div>
    </div>
    <?php if ((int) ($outbox['pending'] ?? 0) > 200): ?>
      <div class="small mt" style="color:var(--warning,#9A5A00)">
        A large backlog usually means the cron is not running. Check the cron job for
        <code>cron_referrals.php outbox</code>.
      </div>
    <?php endif; ?>
  </div>

  <div class="card">
    <h3><?= icon('shield', 18) ?> Blocked / suspicious devices</h3>
    <div class="small muted">
      One handset may redeem a referral code once, ever. This is what stops the
      install &rarr; new SIM &rarr; uninstall &rarr; reinstall loop.
    </div>
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
  <h2>Programme settings</h2>
  <div class="small muted">
    These govern real money. Every change is written to the audit log.
  </div>

  <form method="post" action="<?= e(url('/referrals/settings')) ?>" class="mt">
    <?= App\Core\Csrf::field() ?>

    <h3 class="mt">Switches</h3>
    <div class="grid cards">
      <div class="card">
        <label class="row" style="gap:10px;align-items:flex-start">
          <input type="checkbox" name="referral_enabled" value="1" <?= $programmeOn ? 'checked' : '' ?>>
          <span>
            <strong>Referral programme on</strong>
            <div class="small muted">Off stops new attributions and all commission accrual. Existing balances are untouched.</div>
          </span>
        </label>
      </div>
      <div class="card">
        <label class="row" style="gap:10px;align-items:flex-start">
          <input type="checkbox" name="referral_payouts_enabled" value="1" <?= $payoutsOn ? 'checked' : '' ?>>
          <span>
            <strong>Automatic M-Pesa payouts on</strong>
            <div class="small muted">
              The kill switch. Off means requests are refused cleanly and no money can leave the account.
            </div>
          </span>
        </label>
      </div>
      <div class="card">
        <label class="row" style="gap:10px;align-items:flex-start">
          <input type="checkbox" name="referral_bonus_requires_purchase" value="1"
                 <?= (int) $settings['referral_bonus_requires_purchase'] === 1 ? 'checked' : '' ?>>
          <span>
            <strong>Signup bonus only unlocks after the friend buys</strong>
            <div class="small muted">
              Strongly recommended. The bonus still shows immediately, but cannot be withdrawn until that
              referee pays for something — which is what makes SIM-farming it unprofitable.
            </div>
          </span>
        </label>
      </div>
    </div>

    <h3 class="mt">Earning</h3>
    <div class="pay-filters">
      <div class="field">
        <label for="s-bps">Default commission rate (basis points)</label>
        <input id="s-bps" type="number" name="referral_commission_bps" min="0" max="10000"
               value="<?= (int) $settings['referral_commission_bps'] ?>">
        <div class="small muted">
          100 = 1%. Currently <?= e($pct((int) $settings['referral_commission_bps'])) ?>.
          A per-offer rate on the offer form overrides this, and never exceeds that offer's margin.
        </div>
      </div>
      <div class="field">
        <label for="s-bonus">Signup bonus (cents)</label>
        <input id="s-bonus" type="number" name="referral_signup_bonus_cents" min="0"
               value="<?= (int) $settings['referral_signup_bonus_cents'] ?>">
        <div class="small muted">Currently <?= e($ksh((int) $settings['referral_signup_bonus_cents'])) ?> per person who joins with a code.</div>
      </div>
      <div class="field">
        <label for="s-hold">Hold window (hours)</label>
        <input id="s-hold" type="number" name="referral_hold_hours" min="0" max="720"
               value="<?= (int) $settings['referral_hold_hours'] ?>">
        <div class="small muted">Earnings are not withdrawable until this passes. Closes buy &rarr; earn &rarr; withdraw &rarr; dispute.</div>
      </div>
    </div>

    <h3 class="mt">Withdrawals</h3>
    <div class="pay-filters">
      <div class="field">
        <label for="s-min">Minimum withdrawal (cents)</label>
        <input id="s-min" type="number" name="referral_min_withdraw_cents" min="100"
               value="<?= (int) $settings['referral_min_withdraw_cents'] ?>">
        <div class="small muted">Currently <?= e($ksh((int) $settings['referral_min_withdraw_cents'])) ?>.</div>
      </div>
      <div class="field">
        <label for="s-max">Maximum per withdrawal (cents)</label>
        <input id="s-max" type="number" name="referral_max_withdraw_cents" min="100"
               value="<?= (int) $settings['referral_max_withdraw_cents'] ?>">
        <div class="small muted">Currently <?= e($ksh((int) $settings['referral_max_withdraw_cents'])) ?>.</div>
      </div>
      <div class="field">
        <label for="s-cool">Cooldown between payouts (hours)</label>
        <input id="s-cool" type="number" name="referral_cooldown_hours" min="0" max="720"
               value="<?= (int) $settings['referral_cooldown_hours'] ?>">
        <div class="small muted">Each payout costs you an M-Pesa charge, so many tiny withdrawals are pure loss.</div>
      </div>
      <div class="field">
        <label for="s-cap">Business-wide daily payout cap (cents)</label>
        <input id="s-cap" type="number" name="referral_daily_cap_cents" min="0"
               value="<?= (int) $settings['referral_daily_cap_cents'] ?>">
        <div class="small muted">
          The circuit breaker: <?= e($ksh((int) $settings['referral_daily_cap_cents'])) ?> a day, whatever else goes wrong.
        </div>
      </div>
      <div class="field">
        <label for="s-floor">Float alert floor (cents)</label>
        <input id="s-floor" type="number" name="referral_float_floor_cents" min="0"
               value="<?= (int) $settings['referral_float_floor_cents'] ?>">
        <div class="small muted">Warn when the B2C utility account drops below this. It is not topped up by your Till.</div>
      </div>
    </div>

    <h3 class="mt">Anti-fraud</h3>
    <div class="pay-filters">
      <div class="field">
        <label for="s-dev">Maximum numbers per handset</label>
        <input id="s-dev" type="number" name="referral_max_device_msisdns" min="1" max="20"
               value="<?= (int) $settings['referral_max_device_msisdns'] ?>">
        <div class="small muted">A phone shared in a household reaches 2–3. A farm keeps climbing; past this it is blocked.</div>
      </div>
      <div class="field">
        <label for="s-vel">Maximum referrals per referrer per day</label>
        <input id="s-vel" type="number" name="referral_max_daily_referrals" min="1" max="500"
               value="<?= (int) $settings['referral_max_daily_referrals'] ?>">
      </div>
      <div class="field">
        <label for="s-earn">Maximum earned per referrer per day (cents)</label>
        <input id="s-earn" type="number" name="referral_max_daily_earn_cents" min="0"
               value="<?= (int) $settings['referral_max_daily_earn_cents'] ?>">
        <div class="small muted">Past this the account is parked for review. A delayed payout is recoverable; a drained float is not.</div>
      </div>
      <div class="field">
        <label for="s-sms">Maximum "someone joined" SMS per referrer per day</label>
        <input id="s-sms" type="number" name="referral_join_sms_daily_cap" min="0" max="200"
               value="<?= (int) $settings['referral_join_sms_daily_cap'] ?>">
        <div class="small muted">Stops anyone guessing codes to burn your SMS credit.</div>
      </div>
    </div>

    <div class="row mt">
      <button class="btn" type="submit"><?= icon('check', 16) ?> Save settings</button>
    </div>
  </form>
</div>

<div class="card">
  <h3><?= icon('audit', 18) ?> Recent money events</h3>
  <div class="small muted">Every attribution, accrual, payout and rejection, newest first.</div>
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
