<?php
/**
 * The customer register: who is using the app.
 *
 * The app submits a name and a Safaricom number ONCE, at the end of onboarding
 * (`mybingwa-api/register_user.php`). This page reads that register, and the only
 * writes it offers are removals — there is nothing to edit here, because the
 * customer owns their own details on their phone.
 *
 * Query parameters on GET /customers:
 *   q         name or number (digits are matched however the number was typed)
 *   from/to   Y-m-d, Africa/Nairobi days
 *   page      1-based
 */

namespace App\Controllers;

use App\Core\Audit;
use App\Core\Csrf;
use App\Core\Flash;
use App\Core\Request;
use App\Repositories\CustomerRepository;
use App\Support\Csv;

final class CustomersController extends Controller
{
    private const PER_PAGE = 50;

    /** Ceiling on a single bulk delete, so a crafted form cannot clear the register. */
    private const MAX_BULK = 500;

    public function index(Request $request): void
    {
        $this->guard('customers.view');

        $filters = self::readFilters($request);
        $page = max(1, (int) $request->get('page', 1));
        $found = CustomerRepository::search($filters, $page, self::PER_PAGE);

        $this->view('customers/index', [
            'activeNav' => 'customers',
            'pageTitle' => 'Customers',
            'customers' => $found['rows'],
            'total' => $found['total'],
            'page' => $page,
            'perPage' => self::PER_PAGE,
            'pages' => (int) ceil(max(1, $found['total']) / self::PER_PAGE),
            'filters' => $filters,
            'summary' => CustomerRepository::summary(),
        ]);
    }

    public function delete(Request $request, string $id): void
    {
        Csrf::check($request);
        $this->guard('customers.delete');

        $customer = CustomerRepository::find((int) $id);
        if (!$customer) {
            Flash::error('Customer not found.');
            $this->redirect('/customers');
        }
        CustomerRepository::delete((int) $id);
        Audit::log([
            'action' => 'customer.delete',
            'entity_type' => 'customer',
            'entity_id' => (string) $id,
            'before' => $customer,
        ]);
        Flash::success('Customer removed from the register.');
        $this->redirect('/customers');
    }

    public function deleteBulk(Request $request): void
    {
        Csrf::check($request);
        $this->guard('customers.delete');

        $raw = $request->post('ids', []);
        $ids = array_values(array_unique(array_filter(array_map('intval', (array) $raw))));
        if ($ids === []) {
            Flash::error('Select at least one customer to remove.');
            $this->redirect('/customers');
        }
        if (count($ids) > self::MAX_BULK) {
            $ids = array_slice($ids, 0, self::MAX_BULK);
        }

        $removed = CustomerRepository::deleteMany($ids);
        Audit::log([
            'action' => 'customer.delete_bulk',
            'entity_type' => 'customer',
            'entity_id' => 'bulk',
            'before' => ['ids' => $ids, 'requested' => count($ids)],
            'after' => ['removed' => $removed],
        ]);
        Flash::success($removed === 1 ? '1 customer removed.' : "{$removed} customers removed.");
        $this->redirect('/customers');
    }

    /** Everything the current filters match, as CSV — not just the visible page. */
    public function exportCsv(Request $request): void
    {
        $this->guard('customers.view');

        $rows = CustomerRepository::all(self::readFilters($request));
        Csv::stream(
            'skylinkbingwa-customers.csv',
            ['name', 'phone', 'phone_international', 'joined', 'last_seen', 'app_version', 'registrations'],
            array_map(fn($c) => [
                $c['name'],
                CustomerRepository::displayNumber((string) $c['msisdn']),
                '+' . preg_replace('/\D/', '', (string) $c['msisdn']),
                \App\Repositories\PaymentRepository::nairobiTime($c['created_at'] ?? null),
                \App\Repositories\PaymentRepository::nairobiTime($c['updated_at'] ?? null),
                $c['app_version'],
                (int) ($c['registrations'] ?? 1),
            ], $rows)
        );
    }

    private static function readFilters(Request $request): array
    {
        return [
            'q' => trim((string) $request->get('q', '')),
            'from' => self::dayOrEmpty($request->get('from', '')),
            'to' => self::dayOrEmpty($request->get('to', '')),
        ];
    }

    /** A Y-m-d day, or '' when the value is absent or malformed (never an error). */
    private static function dayOrEmpty($value): string
    {
        $text = trim((string) $value);
        return preg_match('/^\d{4}-\d{2}-\d{2}$/', $text) ? $text : '';
    }
}
