<?php
/**
 * JSON import for billboards and notifications — paste it or upload a file.
 *
 * Everything imported lands as a DRAFT, so an import can never put an advert or a
 * message in front of a customer on its own: the operator opens each one in the
 * normal form, edits it, and publishes through the usual gate. The whole file is
 * validated before a single row is written, so a bad entry rejects the batch
 * instead of leaving half an import behind (see JsonImporter).
 *
 * `?sample=1` downloads the same template the page shows, so the format is never
 * something anyone has to guess or copy out of documentation.
 */

namespace App\Controllers;

use App\Core\Audit;
use App\Core\Csrf;
use App\Core\Database;
use App\Core\Flash;
use App\Core\Request;
use App\Services\JsonImporter;
use App\Services\NotificationService;

final class ImportController extends Controller
{
    /* ------------------------------------------------------------- billboards */

    public function billboardsForm(Request $request): void
    {
        $this->guard('billboards.manage');
        if ($request->get('sample') !== null) {
            self::downloadSample('skylinkbingwa-billboards-template.json', JsonImporter::billboardSample());
        }
        $this->view('import/index', [
            'activeNav' => 'billboards',
            'pageTitle' => 'Import billboards',
            'kind' => 'billboards',
            'heading' => 'Import billboards from JSON',
            'backUrl' => '/billboards',
            'postUrl' => '/billboards/import',
            'sampleUrl' => '/billboards/import?sample=1',
            'sample' => JsonImporter::billboardSample(),
            'fields' => self::billboardFieldHelp(),
        ]);
    }

    public function importBillboards(Request $request): void
    {
        Csrf::check($request);
        $this->guard('billboards.manage');

        $payload = JsonImporter::readPayload($_FILES['file'] ?? null, (string) $request->post('json', ''));
        if (!$payload['ok']) {
            Flash::error($payload['error']);
            $this->redirect('/billboards/import');
        }
        $decoded = JsonImporter::decode($payload['raw'], 'billboards');
        if (!$decoded['ok']) {
            Flash::error($decoded['error']);
            $this->redirect('/billboards/import');
        }

        $categoryKeys = array_column(
            Database::fetchAll('SELECT category_key FROM ' . Database::table('offer_categories')),
            'category_key'
        );
        $result = JsonImporter::validateBillboards($decoded['items'], $categoryKeys);
        if (!$result['ok']) {
            Flash::error('Nothing was imported. ' . implode(' ', array_slice($result['errors'], 0, 6)));
            $this->redirect('/billboards/import');
        }

        $written = JsonImporter::insertBillboards($result['rows']);
        Audit::log([
            'action' => 'billboard.import',
            'entity_type' => 'billboard',
            'entity_id' => 'import',
            'after' => ['imported' => $written, 'names' => array_column($result['rows'], 'name')],
        ]);
        Flash::success(
            ($written === 1 ? '1 billboard' : "{$written} billboards")
            . ' imported as drafts. Review each one, then publish to push them to the app.'
        );
        $this->redirect('/billboards');
    }

    /* ---------------------------------------------------------- notifications */

    public function notificationsForm(Request $request): void
    {
        $this->guard('notifications.create');
        if ($request->get('sample') !== null) {
            self::downloadSample('skylinkbingwa-notifications-template.json', JsonImporter::notificationSample());
        }
        $this->view('import/index', [
            'activeNav' => 'notifications',
            'pageTitle' => 'Import notifications',
            'kind' => 'notifications',
            'heading' => 'Import notifications from JSON',
            'backUrl' => '/notifications',
            'postUrl' => '/notifications/import',
            'sampleUrl' => '/notifications/import?sample=1',
            'sample' => JsonImporter::notificationSample(),
            'fields' => self::notificationFieldHelp(),
        ]);
    }

    public function importNotifications(Request $request): void
    {
        Csrf::check($request);
        $this->guard('notifications.create');

        $payload = JsonImporter::readPayload($_FILES['file'] ?? null, (string) $request->post('json', ''));
        if (!$payload['ok']) {
            Flash::error($payload['error']);
            $this->redirect('/notifications/import');
        }
        $decoded = JsonImporter::decode($payload['raw'], 'notifications');
        if (!$decoded['ok']) {
            Flash::error($decoded['error']);
            $this->redirect('/notifications/import');
        }

        $result = JsonImporter::validateNotifications(
            $decoded['items'],
            NotificationService::categories(),
            NotificationService::triggers(),
            NotificationService::variables()
        );
        if (!$result['ok']) {
            Flash::error('Nothing was imported. ' . implode(' ', array_slice($result['errors'], 0, 6)));
            $this->redirect('/notifications/import');
        }

        $written = JsonImporter::insertNotifications($result['rows']);
        Audit::log([
            'action' => 'notification.import',
            'entity_type' => 'notification',
            'entity_id' => 'import',
            'after' => ['imported' => $written, 'names' => array_column(array_column($result['rows'], 'campaign'), 'name')],
        ]);
        Flash::success(
            ($written === 1 ? '1 notification' : "{$written} notifications")
            . ' imported as drafts. Review each one, then publish to push them to the app.'
        );
        $this->redirect('/notifications');
    }

    /* ------------------------------------------------------------------ helpers */

    private static function downloadSample(string $filename, array $sample): void
    {
        header('Content-Type: application/json; charset=utf-8');
        header('Content-Disposition: attachment; filename="' . $filename . '"');
        header('X-Content-Type-Options: nosniff');
        echo json_encode($sample, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        exit;
    }

    /** @return array<int, array{0:string, 1:string, 2:string}> field, required, meaning */
    private static function billboardFieldHelp(): array
    {
        return [
            ['name', 'required', 'Your own label for the advert. Never shown to a customer.'],
            ['kind', 'optional', '"simple" (built from a linked offer, default) or "advanced" (your own words).'],
            ['linkedOfferId', 'required for simple', 'An existing offer id, e.g. data_6.'],
            ['headline', 'required for advanced', 'The big line. A simple billboard may use {{allowance}}, {{price}}, {{validity}}.'],
            ['body', 'optional', 'The supporting line, same token rules as the headline.'],
            ['tag', 'optional', 'Small chip, e.g. "Popular".'],
            ['ctaLabel', 'optional', 'Button text. Defaults to "Buy now".'],
            ['targetAction', 'optional', 'What a tap does: none, offer, category, internal or url.'],
            ['targetCategory', 'with category', 'DATA, SMS, MINUTES or SPECIAL.'],
            ['clickUrl', 'with url', 'Must start with http:// or https://.'],
            ['internalAction', 'with internal', 'An in-app route, e.g. offers.'],
            ['priority / displayOrder', 'optional', 'Lower shows first. Both default sensibly.'],
            ['startsAt / endsAt', 'advanced only', '"YYYY-MM-DD HH:MM", Nairobi time. Simple billboards are always on.'],
            ['frequencyCap', 'optional', 'Max times per customer per day. 0 = no cap.'],
            ['enabled', 'optional', 'true (default) or false.'],
        ];
    }

    private static function notificationFieldHelp(): array
    {
        $categories = implode(', ', array_keys(NotificationService::categories()));
        $triggers = implode(', ', array_keys(NotificationService::triggers()));
        $variables = array_keys(NotificationService::variables());
        $variableList = $variables === []
            ? 'none are configured yet'
            : implode(', ', array_map(static fn($k) => '{{' . $k . '}}', $variables));

        return [
            ['name', 'required', 'Your own label for the message. Never shown to a customer.'],
            ['category', 'required', 'One of: ' . ($categories ?: 'none configured yet') . '.'],
            ['triggerType', 'required', 'One of: ' . ($triggers ?: 'none configured yet') . '.'],
            ['triggerEvent', 'sometimes', 'Only for triggers that react to a phone message, e.g. low_data.'],
            ['wordings', 'required', 'A list of { "title", "body" } pairs. One is picked at random so the message never reads the same twice.'],
            ['', '', 'Supported variables: ' . $variableList . '.'],
            ['priority', 'optional', 'low, normal (default) or high.'],
            ['allowedTimeStart / allowedTimeEnd', 'optional', '"HH:MM" Nairobi. Blank = any time inside quiet hours.'],
            ['daysOfWeek', 'optional', 'e.g. ["mon","tue"]. Blank = every day.'],
            ['startsOn / endsOn', 'optional', '"YYYY-MM-DD".'],
            ['frequencyCap', 'optional', 'Max times per customer per day. Defaults to 1.'],
            ['cooldownMinutes', 'optional', 'Minimum gap between two showings. Max ' . NotificationService::MAX_COOLDOWN_MINUTES . ' (7 days).'],
            ['respectQuietHours', 'optional', 'true (default) keeps it out of the night.'],
            ['suppressRecentPurchase', 'optional', 'true (default) stays quiet just after someone buys.'],
            ['deepLink', 'optional', 'Where a tap lands: home, offers, activity, help, settings.'],
            ['enabled', 'optional', 'true (default) or false.'],
        ];
    }
}
