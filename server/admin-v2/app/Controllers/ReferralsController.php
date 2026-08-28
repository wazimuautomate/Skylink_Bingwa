<?php
/**
 * Referrals & commissions.
 *
 * Four screens: an overview with the numbers that matter, the referrer list, one
 * referrer's full ledger, and the withdrawals queue. Plus the settings that
 * govern the whole programme.
 *
 * TWO RULES THIS CONTROLLER HOLDS TO
 *
 * 1. No action here writes a balance. Every money change is a ledger entry via
 *    ReferralRepository, so the history stays reconstructible and the nightly
 *    integrity job can prove the cached figures are honest.
 *
 * 2. The commission rate is capped by the recorded margin. A rate above margin
 *    means the harder the referral programme works, the faster the business
 *    loses money — so the form refuses it rather than trusting the operator to
 *    remember.
 *
 * @see docs/REFERRAL_COMMISSION_SPEC.md
 */

namespace App\Controllers;

use App\Core\Audit;
use App\Core\Csrf;
use App\Core\Database;
use App\Core\Flash;
use App\Core\Request;
use App\Repositories\ReferralRepository;
use App\Services\Settings;
use App\Support\Csv;

final class ReferralsController extends Controller
{
    private const PER_PAGE = 50;

    /** Every tunable, with the safe default used when the row is missing. */
    private const SETTINGS = [
        'referral_enabled'                  => 1,
        'referral_commission_bps'           => 1000,
        'referral_signup_bonus_cents'       => 1000,
        'referral_bonus_requires_purchase'  => 1,
        'referral_hold_hours'               => 24,
        'referral_min_withdraw_cents'       => 20000,
        'referral_max_withdraw_cents'       => 1000000,
        'referral_cooldown_hours'           => 24,
        'referral_daily_cap_cents'          => 5000000,
        'referral_payouts_enabled'          => 0,
        'referral_float_floor_cents'        => 5000000,
        'referral_max_device_msisdns'       => 3,
        'referral_max_daily_referrals'      => 15,
        'referral_max_daily_earn_cents'     => 50000,
        'referral_join_sms_daily_cap'       => 20,
    ];

    /* ---------------------------------------------------------------- screens */

    public function index(Request $request): void
    {
        $this->guard('referrals.view');

        $this->view('referrals/index', [
            'activeNav' => 'referrals',
            'pageTitle' => 'Referrals & commissions',
            'summary'   => ReferralRepository::summary(),
            'drift'     => ReferralRepository::drift(),
            'outbox'    => ReferralRepository::outboxHealth(),
            'devices'   => ReferralRepository::blockedDevices(20),
            'events'    => ReferralRepository::recentEvents(40),
            'settings'  => self::currentSettings(),
        ]);
    }

    public function referrers(Request $request): void
    {
        $this->guard('referrals.view');

        $filters = [
            'q'            => trim((string) $request->get('q', '')),
            'status'       => (string) $request->get('status', ''),
            'withdrawable' => $request->get('withdrawable') !== null,
        ];
        $page = max(1, (int) $request->get('page', 1));
        $found = ReferralRepository::search($filters, $page, self::PER_PAGE);

        $this->view('referrals/referrers', [
            'activeNav' => 'referrals',
            'pageTitle' => 'Referrers',
            'rows'      => $found['rows'],
            'total'     => $found['total'],
            'page'      => $page,
            'pages'     => (int) ceil(max(1, $found['total']) / self::PER_PAGE),
            'filters'   => $filters,
        ]);
    }

    public function show(Request $request, string $id): void
    {
        $this->guard('referrals.view');

        $referrer = ReferralRepository::find((int) $id);
        if (!$referrer) {
            Flash::error('Referrer not found.');
            $this->redirect('/referrals/referrers');
        }

        $this->view('referrals/show', [
            'activeNav' => 'referrals',
            'pageTitle' => 'Referrer ' . $referrer['code'],
            'referrer'  => $referrer,
            'balances'  => ReferralRepository::balances((int) $id),
            'ledger'    => ReferralRepository::ledger((int) $id),
            'referees'  => ReferralRepository::referees((int) $referrer['customer_id']),
            'settings'  => self::currentSettings(),
        ]);
    }

    public function withdrawals(Request $request): void
    {
        $this->guard('referrals.view');

        $filters = [
            'status' => (string) $request->get('status', ''),
            'q'      => trim((string) $request->get('q', '')),
        ];
        $page = max(1, (int) $request->get('page', 1));
        $found = ReferralRepository::withdrawals($filters, $page, self::PER_PAGE);

        $this->view('referrals/withdrawals', [
            'activeNav' => 'referrals',
            'pageTitle' => 'Withdrawals',
            'rows'      => $found['rows'],
            'total'     => $found['total'],
            'page'      => $page,
            'pages'     => (int) ceil(max(1, $found['total']) / self::PER_PAGE),
            'filters'   => $filters,
            'settings'  => self::currentSettings(),
        ]);
    }

    /* ---------------------------------------------------------------- actions */

    public function saveSettings(Request $request): void
    {
        Csrf::check($request);
        $this->guard('referrals.manage');

        $before = self::currentSettings();
        $after = [];

        // The form collects money in shillings and the rate as a plain percent —
        // both are converted here to the cents/basis-points this table has always
        // stored, so nothing downstream (ledger, cron, app) needs to know the form
        // ever showed anything else.
        $moneyFieldsKsh = [
            'referral_signup_bonus_cents'   => 'referral_signup_bonus_ksh',
            'referral_min_withdraw_cents'   => 'referral_min_withdraw_ksh',
            'referral_max_withdraw_cents'   => 'referral_max_withdraw_ksh',
            'referral_daily_cap_cents'      => 'referral_daily_cap_ksh',
            'referral_float_floor_cents'    => 'referral_float_floor_ksh',
            'referral_max_daily_earn_cents' => 'referral_max_daily_earn_ksh',
        ];
        $checkboxes = ['referral_enabled', 'referral_payouts_enabled', 'referral_bonus_requires_purchase'];

        foreach (array_keys(self::SETTINGS) as $key) {
            if ($key === 'referral_commission_bps') {
                $pct = (float) $request->post('referral_commission_pct', 0);
                $after[$key] = max(0, (int) round($pct * 100));
                continue;
            }
            if (isset($moneyFieldsKsh[$key])) {
                $ksh = (float) $request->post($moneyFieldsKsh[$key], 0);
                $after[$key] = max(0, (int) round($ksh * 100));
                continue;
            }
            $raw = $request->post($key);
            // Checkboxes post nothing when unticked, which is exactly "0".
            $after[$key] = in_array($key, $checkboxes, true)
                ? ($raw !== null ? 1 : 0)
                : max(0, (int) $raw);
        }

        // A commission rate cannot exceed 100%, and a maximum below the minimum
        // would make every withdrawal impossible while looking configured.
        $after['referral_commission_bps'] = min(10000, $after['referral_commission_bps']);
        if ($after['referral_max_withdraw_cents'] < $after['referral_min_withdraw_cents']) {
            $after['referral_max_withdraw_cents'] = $after['referral_min_withdraw_cents'];
            Flash::error('Maximum withdrawal was below the minimum; it has been raised to match.');
        }

        foreach ($after as $key => $value) {
            Settings::set($key, (string) $value);
        }

        Audit::log([
            'action'      => 'referrals.settings',
            'entity_type' => 'referral_settings',
            'entity_id'   => 'global',
            'before'      => $before,
            'after'       => $after,
        ]);

        // The kill switch deserves an unmistakable message either way.
        if ((int) $before['referral_payouts_enabled'] !== (int) $after['referral_payouts_enabled']) {
            Flash::success($after['referral_payouts_enabled']
                ? 'Settings saved. AUTOMATIC PAYOUTS ARE NOW ON — withdrawals will be sent to M-Pesa.'
                : 'Settings saved. AUTOMATIC PAYOUTS ARE NOW OFF — no money will leave the account.');
        } else {
            Flash::success('Referral settings saved.');
        }
        $this->redirect('/referrals');
    }

    public function setStatus(Request $request, string $id): void
    {
        Csrf::check($request);
        $this->guard('referrals.manage');

        $referrer = ReferralRepository::find((int) $id);
        if (!$referrer) {
            Flash::error('Referrer not found.');
            $this->redirect('/referrals/referrers');
        }

        $status = (string) $request->post('status', 'ACTIVE');
        $allowed = ['ACTIVE', 'EARN_BLOCKED', 'PAYOUT_BLOCKED', 'BANNED'];
        if (!in_array($status, $allowed, true)) {
            Flash::error('Unknown status.');
            $this->redirect('/referrals/referrers/' . (int) $id);
        }

        $reason = trim((string) $request->post('reason', ''));
        ReferralRepository::setStatus((int) $id, $status, $reason);

        Audit::log([
            'action'      => 'referrals.status',
            'entity_type' => 'referrer',
            'entity_id'   => (string) $id,
            'before'      => ['status' => $referrer['status'], 'reason' => $referrer['status_reason']],
            'after'       => ['status' => $status, 'reason' => $reason],
        ]);
        Flash::success('Referrer ' . $referrer['code'] . ' set to ' . $status . '.');
        $this->redirect('/referrals/referrers/' . (int) $id);
    }

    public function adjust(Request $request, string $id): void
    {
        Csrf::check($request);
        $this->guard('referrals.manage');

        $referrer = ReferralRepository::find((int) $id);
        if (!$referrer) {
            Flash::error('Referrer not found.');
            $this->redirect('/referrals/referrers');
        }

        // Entered in shillings; stored in cents like everything else.
        $shillings = (float) $request->post('amount', 0);
        $amountCents = (int) round($shillings * 100);
        $note = trim((string) $request->post('note', ''));

        if ($amountCents === 0) {
            Flash::error('Enter an amount to add or subtract.');
            $this->redirect('/referrals/referrers/' . (int) $id);
        }
        if ($note === '') {
            // An unexplained money movement is worthless in an audit six months later.
            Flash::error('A reason is required for a manual adjustment.');
            $this->redirect('/referrals/referrers/' . (int) $id);
        }

        $actor = (string) (\App\Core\Auth::user()['email'] ?? 'admin');
        $ok = ReferralRepository::adjust((int) $id, $amountCents, $note, $actor);

        Audit::log([
            'action'      => 'referrals.adjust',
            'entity_type' => 'referrer',
            'entity_id'   => (string) $id,
            'after'       => ['amount_cents' => $amountCents, 'note' => $note, 'applied' => $ok],
        ]);

        if ($ok) {
            Flash::success('Adjustment of Ksh ' . number_format($amountCents / 100, 2) . ' recorded.');
        } else {
            Flash::error('That adjustment was already recorded — nothing was posted twice.');
        }
        $this->redirect('/referrals/referrers/' . (int) $id);
    }

    public function resolveWithdrawal(Request $request, string $id): void
    {
        Csrf::check($request);
        $this->guard('referrals.manage');

        $outcome = (string) $request->post('outcome', '');
        if (!in_array($outcome, ['PAID', 'FAILED'], true)) {
            Flash::error('Choose whether M-Pesa actually paid it or not.');
            $this->redirect('/referrals/withdrawals');
        }

        $note = trim((string) $request->post('note', ''));
        if ($note === '') {
            Flash::error('Record what you checked before closing a payout by hand.');
            $this->redirect('/referrals/withdrawals');
        }

        $before = ReferralRepository::findWithdrawal((int) $id);
        $actor = (string) (\App\Core\Auth::user()['email'] ?? 'admin');
        $ok = ReferralRepository::resolveWithdrawal((int) $id, $outcome, $note, $actor);

        Audit::log([
            'action'      => 'referrals.withdrawal.resolve',
            'entity_type' => 'withdrawal',
            'entity_id'   => (string) $id,
            'before'      => $before ? ['status' => $before['status']] : null,
            'after'       => ['status' => $outcome, 'note' => $note],
        ]);

        if ($ok) {
            Flash::success($outcome === 'PAID'
                ? 'Marked paid. The hold on the referrer\'s balance is now final.'
                : 'Marked failed. The money is back in the referrer\'s balance.');
        } else {
            Flash::error('That withdrawal is already resolved — nothing changed.');
        }
        $this->redirect('/referrals/withdrawals');
    }

    public function exportCsv(Request $request): void
    {
        $this->guard('referrals.view');

        $found = ReferralRepository::search(
            ['q' => trim((string) $request->get('q', '')), 'status' => (string) $request->get('status', '')],
            1,
            5000
        );

        $header = ['Code', 'Name', 'Number', 'Status', 'Referrals', 'Balance (Ksh)', 'Lifetime earned (Ksh)', 'Lifetime paid (Ksh)', 'Verified'];
        $rows = [];
        foreach ($found['rows'] as $r) {
            $rows[] = [
                $r['code'],
                $r['name'],
                $r['msisdn'],
                $r['status'],
                (int) $r['referrals_count'],
                number_format(((int) $r['balance_cents']) / 100, 2, '.', ''),
                number_format(((int) $r['lifetime_earned_cents']) / 100, 2, '.', ''),
                number_format(((int) $r['lifetime_paid_cents']) / 100, 2, '.', ''),
                $r['verified_msisdn'] ? 'yes' : 'no',
            ];
        }

        Csv::stream('skylinkbingwa-referrers-' . gmdate('Ymd-Hi') . '.csv', $header, $rows);
    }

    /* ---------------------------------------------------------------- helpers */

    private static function currentSettings(): array
    {
        $out = self::SETTINGS;
        foreach (self::SETTINGS as $key => $default) {
            $value = Settings::get($key, null);
            $out[$key] = $value === null ? $default : (int) $value;
        }
        return $out;
    }
}
