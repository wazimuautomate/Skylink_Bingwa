<?php
/**
 * Payment operations and offer/bundle performance reporting over the real payments table.
 *
 * This is the owner's "how are my bundles performing" page as well as the reconciliation
 * ledger, so it deliberately shows full identifiers (payer, recipient, M-Pesa receipt)
 * unmasked to holders of payments.view. The one write is deleting a payment record —
 * CSRF-checked, guarded by payments.export and audited. Admin V2 never marks an unverified
 * payment successful and never derives per-customer behaviour: every figure below is an
 * aggregate of the seller's own payment records.
 *
 * Everything lives on GET /payments with query parameters, because the /payments/{id}
 * route would swallow any extra path segment:
 *
 *   category  DATA|SMS|MINUTES|SPECIAL|OTHER   buyer  self|other
 *   state     a payment status                 from/to  Y-m-d, Africa/Nairobi days
 *   q         offer id or M-Pesa receipt       min/max  amount bounds
 *   sort      revenue|sales|conversion         page     1-based
 *
 * Unknown or malformed values are ignored rather than raising an error.
 */

namespace App\Controllers;

use App\Core\Audit;
use App\Core\Csrf;
use App\Core\Database;
use App\Core\Flash;
use App\Core\Rbac;
use App\Core\Request;
use App\Repositories\PaymentRepository;
use App\Support\Csv;

final class PaymentsController extends Controller
{
    private const PER_PAGE = 25;

    /**
     * Ceiling on rows pulled into PHP when a filter cannot be expressed in SQL. Beyond
     * this the page reports that older records were not analysed rather than quietly
     * lying about the totals.
     */
    private const MAX_SCAN = 5000;

    /** Categories the offer catalogue defines; anything else is reported as OTHER. */
    private const CATEGORIES = ['DATA', 'SMS', 'MINUTES', 'SPECIAL'];

    /** The payment states the payment API writes. */
    private const STATES = ['PAYMENT_CONFIRMED', 'PAYMENT_REQUESTED', 'PAYMENT_FAILED', 'CANCELLED', 'TIMED_OUT'];

    private const SORTS = ['revenue', 'sales', 'conversion'];

    /** Lower bound for the "all time" analytics window — long before the business existed. */
    private const EARLIEST_DAY = '2000-01-01';

    public function index(Request $request): void
    {
        $this->guard('payments.view');

        $f = self::readFilters($request);
        $page = max(1, self::intOf($request->get('page', 1)));
        $available = PaymentRepository::available();
        $offerMap = self::offerMap();
        [$windowStart, $windowEnd] = self::window($f['from'], $f['to']);

        $found = $available
            ? $this->fetchRows($f, $offerMap, $page, self::PER_PAGE)
            : ['rows' => [], 'total' => 0, 'capped' => false];
        $rows = self::decorate($found['rows'], $offerMap);

        // Analytics follow the date window only. The category filter is applied to the
        // bundle table (it carries a category of its own); the record-level filters
        // (buyer, state, q, min, max) intentionally do not distort the totals the cards
        // report, otherwise "success rate" would always read 100% once a state is chosen.
        $summary    = PaymentRepository::revenueSummary();
        $categories = PaymentRepository::categoryPerformance($windowStart, $windowEnd);
        $trend      = PaymentRepository::buyerTrend($windowStart, $windowEnd);
        $outcomes   = PaymentRepository::statusBreakdown($windowStart, $windowEnd);
        $series     = PaymentRepository::dailySeries(14);
        // When people buy, which kind of bundle they buy, and which bundles have
        // regulars. All three follow the same date window as the cards above, so a
        // figure and the rows behind it never disagree.
        $hourly     = PaymentRepository::hourlyDistribution($windowStart, $windowEnd);
        $policy     = PaymentRepository::policyTrend($windowStart, $windowEnd);
        $regulars   = PaymentRepository::repeatBuyers($windowStart, $windowEnd, 8);

        $bundles = PaymentRepository::offerPerformance($windowStart, $windowEnd, 100);
        if ($f['category'] !== '') {
            $bundles = array_values(array_filter(
                $bundles,
                static fn(array $b): bool => self::normalCategory($b['category']) === $f['category']
            ));
        }
        $bundles = self::sortBundles($bundles, $f['sort']);

        $windowRevenue = 0;
        $windowSales = 0;
        foreach ($categories as $c) {
            $windowRevenue += (int) $c['revenue'];
            $windowSales   += (int) $c['sales'];
        }

        $this->view('payments/index', [
            'activeNav' => 'payments',
            'pageTitle' => 'Payments',
            'available' => $available,
            'rows' => $rows,
            'total' => $found['total'],
            'capped' => $found['capped'],
            'page' => $page,
            'per' => self::PER_PAGE,
            'filters' => $f,
            'states' => self::STATES,
            'categoryKeys' => array_merge(self::CATEGORIES, ['OTHER']),
            'windowLabel' => self::windowLabel($f['from'], $f['to']),
            'summary' => $summary,
            'categories' => $categories,
            'windowRevenue' => $windowRevenue,
            'windowSales' => $windowSales,
            'trend' => $trend,
            'outcomes' => $outcomes,
            'bundles' => $bundles,
            'series' => $series,
            'hourly' => $hourly,
            'policy' => $policy,
            'regulars' => $regulars,
            'canReveal' => Rbac::can('payments.export'),
            'canDelete' => Rbac::can('payments.export'),
        ]);
    }

    public function show(Request $request, string $id): void
    {
        $this->guard('payments.view');
        $row = PaymentRepository::find((int) $id);
        if (!$row) {
            $this->redirect('/payments');
        }
        $offerMap = self::offerMap();
        $offerId = (string) ($row['offer_id'] ?? '');
        $offer = $offerMap[$offerId] ?? null;

        // All-time performance of the offer this payment bought. offerPerformance() groups
        // by offer, so one call covers every offer; a generous limit keeps a quiet bundle
        // in the result rather than only the top sellers.
        $performance = null;
        foreach (PaymentRepository::offerPerformance(null, null, 1000) as $b) {
            if ($b['offer_id'] === $offerId) {
                $performance = $b;
                break;
            }
        }

        $this->view('payments/show', [
            'activeNav' => 'payments',
            'pageTitle' => 'Payment #' . (int) $id,
            'p' => $row,
            'offer' => $offer,
            'offerCategory' => self::normalCategory($offer['category'] ?? ''),
            'buyerKind' => self::buyerKind($row),
            'performance' => $performance,
            'canReveal' => Rbac::can('payments.export'),
            'canDelete' => Rbac::can('payments.export'),
        ]);
    }

    /**
     * CSV of exactly what the screen shows: the same filters (including category and
     * buyer), plus the resolved offer name, category and buyer kind. Csv::stream()
     * neutralises formula injection on every cell.
     */
    public function exportCsv(Request $request): void
    {
        $this->guard('payments.export');
        $f = self::readFilters($request);
        $offerMap = self::offerMap();
        $found = PaymentRepository::available()
            ? $this->fetchRows($f, $offerMap, 1, self::MAX_SCAN)
            : ['rows' => [], 'total' => 0, 'capped' => false];
        $rows = self::decorate($found['rows'], $offerMap);

        Csv::stream(
            'skylinkbingwa-payments.csv',
            ['id', 'time_nairobi', 'payer', 'recipient', 'bought_for', 'offer', 'offer_name',
             'category', 'amount', 'status', 'receipt'],
            array_map(static fn(array $r): array => [
                $r['id'],
                PaymentRepository::nairobiTime($r['created_at'], 'Y-m-d H:i'),
                $r['payer'],
                $r['recipient'] ?: $r['payer'],
                $r['_buyer'] === 'other' ? 'another number' : 'themselves',
                $r['offer_id'],
                $r['_offer_name'],
                $r['_offer_category'],
                $r['amount'],
                PaymentRepository::displayState($r['status'])['label'],
                $r['mpesa_receipt'] ?: '',
            ], $rows)
        );
    }

    /**
     * Delete a payment record. Admin V2 is otherwise read-only over payments, so this is
     * a deliberate capability: CSRF-checked, guarded by the strongest payments permission
     * (payments.export) and fully audited with the deleted row captured before removal.
     */
    public function delete(Request $request, string $id): void
    {
        Csrf::check($request);
        $this->guard('payments.export');
        $row = PaymentRepository::find((int) $id);
        if (!$row) {
            Flash::error('Payment record not found.');
            $this->redirect('/payments');
        }
        PaymentRepository::delete((int) $id);
        Audit::log([
            'action' => 'payment.delete',
            'entity_type' => 'payment',
            'entity_id' => (int) $id,
            'before' => $row,
            'after' => null,
        ]);
        Flash::success('Payment record deleted.');
        $this->redirect('/payments');
    }

    /**
     * Bulk-delete selected payment records to clean the table. Same safeguards as the single
     * delete — CSRF-checked, guarded by payments.export and fully audited. Submitted ids are
     * cast to positive ints, de-duplicated and capped so a crafted request cannot remove an
     * unbounded number of rows in one call.
     */
    public function deleteBulk(Request $request): void
    {
        Csrf::check($request);
        $this->guard('payments.export');
        $ids = array_values(array_unique(array_filter(
            array_map('intval', (array) $request->post('ids', [])),
            static fn(int $id): bool => $id > 0
        )));
        $ids = array_slice($ids, 0, 500);
        if ($ids === []) {
            Flash::error('Select at least one record.');
            $this->redirect('/payments');
        }
        $deleted = PaymentRepository::deleteMany($ids);
        Audit::log([
            'action' => 'payment.delete_bulk',
            'entity_type' => 'payment',
            'before' => ['count' => $deleted, 'ids' => $ids],
            'after' => null,
        ]);
        Flash::success($deleted . ' payment record(s) deleted.');
        $this->redirect('/payments');
    }

    /* ------------------------------------------------------------- filtering */

    /**
     * Read and sanitise every supported query parameter. Anything unrecognised becomes an
     * empty string, so a hand-typed or stale link degrades to "no filter" instead of an
     * error. Reversed date or amount ranges are swapped rather than rejected.
     *
     * @return array{category:string,buyer:string,state:string,from:string,to:string,
     *               q:string,min:string,max:string,sort:string}
     */
    private static function readFilters(Request $request): array
    {
        $category = strtoupper(self::strOf($request->get('category', '')));
        if ($category !== 'OTHER' && !in_array($category, self::CATEGORIES, true)) {
            $category = '';
        }

        $buyer = strtolower(self::strOf($request->get('buyer', '')));
        if ($buyer !== 'self' && $buyer !== 'other') {
            $buyer = '';
        }

        $state = strtoupper(self::strOf($request->get('state', '')));
        if (!in_array($state, self::STATES, true)) {
            $state = '';
        }

        $from = self::dayOf($request->get('from', ''));
        $to   = self::dayOf($request->get('to', ''));
        if ($from !== '' && $to !== '' && $from > $to) {
            [$from, $to] = [$to, $from];
        }

        $q = self::strOf($request->get('q', ''));
        if (strlen($q) > 64) {
            $q = substr($q, 0, 64);
        }

        $min = self::amountOf($request->get('min', ''));
        $max = self::amountOf($request->get('max', ''));
        if ($min !== '' && $max !== '' && (int) $min > (int) $max) {
            [$min, $max] = [$max, $min];
        }

        $sort = strtolower(self::strOf($request->get('sort', '')));
        if (!in_array($sort, self::SORTS, true)) {
            $sort = 'revenue';
        }

        return [
            'category' => $category, 'buyer' => $buyer, 'state' => $state,
            'from' => $from, 'to' => $to, 'q' => $q,
            'min' => $min, 'max' => $max, 'sort' => $sort,
        ];
    }

    /**
     * Fetch the payment rows for a page.
     *
     * PaymentRepository::search() understands state/q/min/max/from/to but knows nothing
     * about `category` or `buyer`, and its from/to are compared against the database
     * server's own clock rather than an Africa/Nairobi day. So whenever a date, category
     * or buyer filter is active we cannot page in SQL: the rows SQL returns for page 3
     * would not be the rows that survive the PHP filter. In that case we fetch a single
     * large slab (capped at MAX_SCAN, newest first), filter it, and page in PHP so the
     * count and the page contents always agree. Without those filters SQL paging is exact
     * and is used unchanged.
     *
     * @return array{rows:array, total:int, capped:bool}
     */
    private function fetchRows(array $f, array $offerMap, int $page, int $perPage): array
    {
        $sqlFilters = [
            'state' => $f['state'],
            'q' => $f['q'],
            'min' => $f['min'],
            'max' => $f['max'],
        ];
        $needsPhp = $f['category'] !== '' || $f['buyer'] !== '' || $f['from'] !== '' || $f['to'] !== '';

        if (!$needsPhp) {
            $result = PaymentRepository::search($sqlFilters, $page, $perPage);
            return ['rows' => $result['rows'], 'total' => $result['total'], 'capped' => false];
        }

        // Widen the SQL date bounds by a day on each side so no row inside the true
        // Nairobi window is lost to a database clock that is not EAT; the exact
        // boundary is then applied below.
        if ($f['from'] !== '') {
            $sqlFilters['from'] = self::shiftDay($f['from'], -1);
        }
        if ($f['to'] !== '') {
            $sqlFilters['to'] = self::shiftDay($f['to'], 1);
        }

        $result = PaymentRepository::search($sqlFilters, 1, self::MAX_SCAN);
        [$windowStart, $windowEnd] = self::window($f['from'], $f['to']);
        $datesActive = $f['from'] !== '' || $f['to'] !== '';

        $kept = [];
        foreach ($result['rows'] as $row) {
            if ($datesActive) {
                // Both sides are 'Y-m-d H:i:s' in the database's own clock, so a plain
                // string comparison is an exact instant comparison.
                $at = (string) ($row['created_at'] ?? '');
                if ($at === '' || $at < $windowStart || $at >= $windowEnd) {
                    continue;
                }
            }
            if ($f['category'] !== '' && self::categoryOf($row, $offerMap) !== $f['category']) {
                continue;
            }
            if ($f['buyer'] !== '' && self::buyerKind($row) !== $f['buyer']) {
                continue;
            }
            $kept[] = $row;
        }

        $offset = max(0, ($page - 1) * $perPage);
        return [
            'rows' => array_slice($kept, $offset, $perPage),
            'total' => count($kept),
            'capped' => $result['total'] > self::MAX_SCAN,
        ];
    }

    /**
     * Attach the resolved offer name/category/price and the buyer kind to each row for
     * display. Underscore-prefixed keys mark them as view additions, not payment columns —
     * the reconciliation modal skips them when listing the raw record.
     */
    private static function decorate(array $rows, array $offerMap): array
    {
        foreach ($rows as $i => $row) {
            $offerId = (string) ($row['offer_id'] ?? '');
            $offer = $offerMap[$offerId] ?? null;
            $rows[$i]['_offer_name'] = $offer['name'] ?? '';
            $rows[$i]['_offer_category'] = self::categoryOf($row, $offerMap);
            $rows[$i]['_buyer'] = self::buyerKind($row);
        }
        return $rows;
    }

    /**
     * The offer catalogue keyed by its app-facing offer id. One small query serves both
     * the category filter and the offer name shown beside every payment, instead of a
     * lookup per row.
     */
    private static function offerMap(): array
    {
        try {
            $rows = Database::fetchAll('SELECT offer_id, name, category, price FROM ' . Database::table('offers'));
        } catch (\Throwable $e) {
            return [];
        }
        $map = [];
        foreach ($rows as $r) {
            $map[(string) $r['offer_id']] = [
                'name' => (string) $r['name'],
                'category' => strtoupper((string) $r['category']),
                'price' => (int) $r['price'],
            ];
        }
        return $map;
    }

    /** The reporting category of a payment: its offer's, or OTHER when the offer is gone. */
    private static function categoryOf(array $row, array $offerMap): string
    {
        $offerId = (string) ($row['offer_id'] ?? '');
        return self::normalCategory($offerMap[$offerId]['category'] ?? '');
    }

    /** Fold an unknown or missing category into OTHER, matching categoryPerformance(). */
    private static function normalCategory(?string $category): string
    {
        $c = strtoupper(trim((string) $category));
        return in_array($c, self::CATEGORIES, true) ? $c : 'OTHER';
    }

    /**
     * 'self' or 'other' — the same rule PaymentRepository uses in SQL: buying for myself
     * leaves the bundle recipient blank or equal to the payer's M-Pesa number.
     */
    private static function buyerKind(array $row): string
    {
        $payer = trim((string) ($row['payer'] ?? ''));
        $recipient = trim((string) ($row['recipient'] ?? ''));
        return ($recipient === '' || $recipient === $payer) ? 'self' : 'other';
    }

    /** Order the bundle table in PHP — it is at most 100 rows already in memory. */
    private static function sortBundles(array $bundles, string $sort): array
    {
        usort($bundles, static function (array $a, array $b) use ($sort): int {
            if ($sort === 'sales') {
                $cmp = $b['sales'] <=> $a['sales'];
            } elseif ($sort === 'conversion') {
                $cmp = $b['conversion'] <=> $a['conversion'];
            } else {
                $cmp = $b['revenue'] <=> $a['revenue'];
            }
            if ($cmp !== 0) {
                return $cmp;
            }
            $cmp = $b['revenue'] <=> $a['revenue'];
            return $cmp !== 0 ? $cmp : strcmp($a['offer_id'], $b['offer_id']);
        });
        return $bundles;
    }

    /* ---------------------------------------------------------------- window */

    /**
     * The analytics window as two datetimes in the database's own clock, built from the
     * Nairobi days the operator chose. An empty `from` reaches back before the business
     * existed and an empty `to` ends at the close of today, so "no dates" means all time.
     *
     * @return array{0:string,1:string}
     */
    private static function window(string $from, string $to): array
    {
        $today = (new \DateTimeImmutable('today', new \DateTimeZone('Africa/Nairobi')))->format('Y-m-d');
        $startDay = $from !== '' ? $from : self::EARLIEST_DAY;
        $endDay   = $to !== '' ? $to : $today;
        if ($endDay < $startDay) {
            $endDay = $startDay;
        }
        [$start] = PaymentRepository::dayWindow($startDay);
        [, $end] = PaymentRepository::dayWindow($endDay);
        return [$start, $end];
    }

    /** Human label for the current analytics window. */
    private static function windowLabel(string $from, string $to): string
    {
        if ($from === '' && $to === '') {
            return 'All time';
        }
        if ($from !== '' && $to !== '') {
            return $from === $to
                ? self::prettyDay($from)
                : self::prettyDay($from) . ' to ' . self::prettyDay($to);
        }
        return $from !== ''
            ? self::prettyDay($from) . ' to today'
            : 'Everything up to ' . self::prettyDay($to);
    }

    private static function prettyDay(string $day): string
    {
        try {
            return (new \DateTimeImmutable($day . ' 00:00:00', new \DateTimeZone('Africa/Nairobi')))->format('j M Y');
        } catch (\Throwable $e) {
            return $day;
        }
    }

    /** Move a Y-m-d day by whole days without depending on the default timezone. */
    private static function shiftDay(string $day, int $days): string
    {
        try {
            return (new \DateTimeImmutable($day . ' 00:00:00', new \DateTimeZone('UTC')))
                ->modify(($days >= 0 ? '+' : '') . $days . ' day')
                ->format('Y-m-d');
        } catch (\Throwable $e) {
            return $day;
        }
    }

    /* --------------------------------------------------------------- casting */

    /** A query value as a trimmed string; arrays and objects become ''. */
    private static function strOf($value): string
    {
        return is_scalar($value) ? trim((string) $value) : '';
    }

    private static function intOf($value): int
    {
        return is_scalar($value) ? (int) $value : 0;
    }

    /** A real calendar day in Y-m-d, else ''. */
    private static function dayOf($value): string
    {
        $s = self::strOf($value);
        if (!preg_match('/^(\d{4})-(\d{2})-(\d{2})$/', $s, $m)) {
            return '';
        }
        return checkdate((int) $m[2], (int) $m[3], (int) $m[1]) ? $s : '';
    }

    /** A non-negative whole shilling amount, else ''. */
    private static function amountOf($value): string
    {
        $s = self::strOf($value);
        return preg_match('/^\d{1,9}$/', $s) ? (string) (int) $s : '';
    }
}
