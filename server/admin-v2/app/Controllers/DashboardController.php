<?php
/**
 * Dashboard: what the shop actually sold.
 *
 * Money in (today and all time), which category sold today, whether customers
 * bought for themselves or for someone else, and how big the catalogue is —
 * then the bundles that performed and the last two weeks of trade.
 *
 * Every payment figure comes from PaymentRepository, which reads the seller's
 * own payment records and already handles the "payments table does not exist
 * yet" case by returning zeros. Nothing here profiles a customer, and the
 * dashboard never shows a full phone number.
 */

namespace App\Controllers;

use App\Core\Auth;
use App\Core\Database;
use App\Core\Request;
use App\Repositories\PaymentRepository;
use App\Support\Csv;

final class DashboardController extends Controller
{
    public function index(Request $request): void
    {
        $this->guard('dashboard.view');

        // Window pairs are produced by the repository in the DATABASE clock and
        // are only ever passed back to it. The Y-m-d dates below are Nairobi
        // dates, used for the payments page's own from/to filters.
        [$dayFrom, $dayTo] = PaymentRepository::dayWindow();
        [$monthFrom, $monthTo] = PaymentRepository::daysWindow(30);

        $today = new \DateTimeImmutable('today', new \DateTimeZone('Africa/Nairobi'));
        $offersTable = Database::table('offers');

        $this->view('dashboard/index', [
            'activeNav'  => 'dashboard',
            'pageTitle'  => 'Dashboard',
            'greeting'   => $this->greeting(),
            'firstName'  => explode(' ', trim((string) (Auth::user()['name'] ?? 'there')))[0],
            'paymentsAvailable' => PaymentRepository::available(),

            // Dates for payments-page filters (Africa/Nairobi).
            'today'  => $today->format('Y-m-d'),
            'from14' => $today->modify('-13 day')->format('Y-m-d'),
            'from30' => $today->modify('-29 day')->format('Y-m-d'),

            // The four cards.
            'revenue'       => PaymentRepository::revenueSummary(),
            'categoryToday' => PaymentRepository::categoryPerformance(),
            'buyerAllTime'  => PaymentRepository::buyerTrend(),
            'buyerToday'    => PaymentRepository::buyerTrend($dayFrom, $dayTo),
            'offerCounts'   => [
                'active' => (int) (Database::scalar("SELECT COUNT(*) FROM {$offersTable} WHERE status='active'") ?? 0),
                'total'  => (int) (Database::scalar("SELECT COUNT(*) FROM {$offersTable}") ?? 0),
            ],

            // Performance section.
            'topOffers' => PaymentRepository::offerPerformance($monthFrom, $monthTo, 10),
            'status30'  => PaymentRepository::statusBreakdown($monthFrom, $monthTo),
            'series'    => PaymentRepository::dailySeries(14),

            'latestPayments' => PaymentRepository::latest(6),
        ]);
    }

    private function greeting(): string
    {
        $h = (int) (new \DateTimeImmutable('now', new \DateTimeZone('Africa/Nairobi')))->format('G');
        if ($h < 12) return 'Good morning';
        if ($h < 17) return 'Good afternoon';
        return 'Good evening';
    }

    public function exportCsv(Request $request): void
    {
        $this->guard('dashboard.view');
        $rows = PaymentRepository::latest(500);
        Csv::stream('skylinkbingwa-payments-summary.csv',
            ['date', 'offer', 'amount', 'status'],
            array_map(fn($r) => [
                // Payments carry the DATABASE clock, not UTC — see nairobiTime().
                PaymentRepository::nairobiTime($r['created_at'], 'Y-m-d H:i'),
                $r['offer_id'], $r['amount'],
                PaymentRepository::displayState($r['status'])['label'],
            ], $rows)
        );
    }
}
