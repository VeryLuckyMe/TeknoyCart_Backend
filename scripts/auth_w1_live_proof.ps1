# ============================================================================
# W1 live escalation proof (PostgREST)
# ============================================================================
# Run this after auth_w1_verify_2026_10_24.sql is GREEN.
#
# The Supabase SQL editor runs as `postgres`; a `SET ROLE authenticated` test
# can still leave `session_user = postgres`, which makes the trigger guard
# return early. This script uses real Supabase Auth sessions and PostgREST, so
# `session_user` is the PostgREST authenticator and the trigger guard is real.
#
# Required environment variables:
#   SUPABASE_URL          https://<project-ref>.supabase.co
#   SUPABASE_ANON_KEY     the public anon key (not the service-role key)
#
# Demo-account variables (prompted if absent):
#   W1_BUYER_EMAIL / W1_BUYER_PASSWORD
#   W1_SELLER_EMAIL / W1_SELLER_PASSWORD
#   W1_PENDING_EMAIL / W1_PENDING_PASSWORD
#   W1_ADMIN_EMAIL / W1_ADMIN_PASSWORD       (optional; admin check skipped)
#
# The script prints PASS/FAIL rows and exits non-zero on any FAIL. It does not
# print passwords or access tokens. The intended write is a temporary seller
# product, which is deleted before exit. Attack writes are expected to be
# denied; if one unexpectedly succeeds, its result prints the row id to clean
# up in the SQL editor.
# ============================================================================

[CmdletBinding()]
param(
    [string]$SupabaseUrl = $env:SUPABASE_URL,
    [string]$AnonKey = $env:SUPABASE_ANON_KEY
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function ConvertTo-PlainText {
    param([Security.SecureString]$Secure)
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

function Read-Value {
    param([string]$Name, [string]$EnvValue, [switch]$Secret)
    if (-not [string]::IsNullOrWhiteSpace($EnvValue)) { return $EnvValue }
    if ($Secret) {
        $secure = Read-Host $Name -AsSecureString
        return ConvertTo-PlainText $secure
    }
    return Read-Host $Name
}

if ([string]::IsNullOrWhiteSpace($SupabaseUrl)) {
    $SupabaseUrl = Read-Value 'Supabase URL' $env:SUPABASE_URL
}
if ([string]::IsNullOrWhiteSpace($AnonKey)) {
    $AnonKey = Read-Value 'Supabase anon key' $env:SUPABASE_ANON_KEY
}
$SupabaseUrl = $SupabaseUrl.TrimEnd('/')

$buyerEmail = Read-Value 'Buyer email' $env:W1_BUYER_EMAIL
$buyerPassword = Read-Value 'Buyer password' $env:W1_BUYER_PASSWORD -Secret
$sellerEmail = Read-Value 'Approved seller email' $env:W1_SELLER_EMAIL
$sellerPassword = Read-Value 'Approved seller password' $env:W1_SELLER_PASSWORD -Secret
$pendingEmail = Read-Value 'Pending seller email' $env:W1_PENDING_EMAIL
$pendingPassword = Read-Value 'Pending seller password' $env:W1_PENDING_PASSWORD -Secret
$adminEmail = $env:W1_ADMIN_EMAIL
$adminPassword = $env:W1_ADMIN_PASSWORD
if (-not [string]::IsNullOrWhiteSpace($adminEmail) -and [string]::IsNullOrWhiteSpace($adminPassword)) {
    $adminPassword = Read-Value 'Admin password' '' -Secret
}

$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(30)
$script:results = [System.Collections.Generic.List[object]]::new()

function Add-Result {
    param(
        [string]$Id,
        [string]$Expected,
        [string]$Actual,
        [bool]$Pass,
        [string]$Detail = ''
    )
    $script:results.Add([pscustomobject]@{
        Proof    = $Id
        Expected = $Expected
        Actual   = $Actual
        Status   = if ($Pass) { 'PASS' } else { 'FAIL' }
        Detail   = $Detail
    }) | Out-Null
}

function Test-Success {
    param([int]$Status)
    return $Status -ge 200 -and $Status -lt 300
}

function Invoke-Supabase {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Token,
        [object]$Body = $null,
        [hashtable]$Headers = @{}
    )

    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method),
        "$SupabaseUrl$Path"
    )
    $request.Headers.TryAddWithoutValidation('apikey', $AnonKey) | Out-Null
    $request.Headers.TryAddWithoutValidation('Authorization', "Bearer $Token") | Out-Null
    foreach ($key in $Headers.Keys) {
        $request.Headers.TryAddWithoutValidation($key, $Headers[$key]) | Out-Null
    }

    if ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 10 -Compress }
        $request.Content = [System.Net.Http.StringContent]::new(
            $json,
            [System.Text.Encoding]::UTF8,
            'application/json'
        )
    }

    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $status = [int]$response.StatusCode
    $response.Dispose()
    $request.Dispose()

    return [pscustomobject]@{ Status = $status; Body = $text }
}

function Get-Token {
    param([string]$Label, [string]$Email, [string]$Password)

    $response = Invoke-Supabase -Method POST -Path '/auth/v1/token?grant_type=password' `
        -Token $AnonKey -Body @{ email = $Email; password = $Password }

    if ($response.Status -ne 200) {
        throw "$Label login failed ($($response.Status)): $($response.Body)"
    }

    $payload = $response.Body | ConvertFrom-Json
    return [pscustomobject]@{
        Label  = $Label
        Token  = $payload.access_token
        UserId = $payload.user.id
    }
}

function Get-JsonArray {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return ,@() }
    try {
        $value = $Text | ConvertFrom-Json
        return ,@($value)
    } catch {
        return ,@()
    }
}

try {
    # ------------------------------------------------------------------------
    # Buyer
    # ------------------------------------------------------------------------
    $buyer = Get-Token 'buyer' $buyerEmail $buyerPassword
    Add-Result 'R00 buyer login' '200' '200' $true 'real Supabase Auth session'

    $response = Invoke-Supabase -Method GET `
        -Path '/rest/v1/users?select=email&limit=1' -Token $buyer.Token
    Add-Result 'R01 buyer cannot read users.email' 'non-2xx' $response.Status `
        (-not (Test-Success $response.Status)) $response.Body

    $response = Invoke-Supabase -Method GET `
        -Path "/rest/v1/users?select=user_id,full_name,role,is_seller_verified,last_seen_at&user_id=eq.$($buyer.UserId)" `
        -Token $buyer.Token
    $rows = Get-JsonArray $response.Body
    $safeSelect = (Test-Success $response.Status) -and $rows.Count -eq 1 -and $rows[0].user_id -eq $buyer.UserId
    Add-Result 'R02 buyer can read safe users columns' '200 / 1 row' "$($response.Status) / $($rows.Count) rows" `
        $safeSelect $response.Body

    $response = Invoke-Supabase -Method GET `
        -Path '/rest/v1/my_profile?select=*' -Token $buyer.Token
    $rows = Get-JsonArray $response.Body
    $ownProfile = (Test-Success $response.Status) -and $rows.Count -eq 1 -and $rows[0].user_id -eq $buyer.UserId
    Add-Result 'R03 my_profile returns exactly the caller row' '200 / 1 own row' "$($response.Status) / $($rows.Count) rows" `
        $ownProfile $response.Body

    $response = Invoke-Supabase -Method PATCH `
        -Path "/rest/v1/users?user_id=eq.$($buyer.UserId)" -Token $buyer.Token `
        -Body @{ role = 'ADMIN' } -Headers @{ Prefer = 'return=representation' }
    $rolePatchDenied = -not (Test-Success $response.Status)
    Add-Result 'R04 buyer cannot PATCH role=ADMIN' 'non-2xx' $response.Status `
        $rolePatchDenied $response.Body

    $response = Invoke-Supabase -Method GET `
        -Path "/rest/v1/users?select=role&user_id=eq.$($buyer.UserId)" -Token $buyer.Token
    $rows = Get-JsonArray $response.Body
    $roleStillBuyer = (Test-Success $response.Status) -and $rows.Count -eq 1 -and $rows[0].role -eq 'BUYER'
    Add-Result 'R05 buyer role unchanged after PATCH' 'BUYER' $(if ($rows.Count -gt 0) { $rows[0].role } else { 'missing' }) `
        $roleStillBuyer $response.Body

    $adminUserId = [guid]::NewGuid().ToString()
    $response = Invoke-Supabase -Method POST `
        -Path '/rest/v1/users' -Token $buyer.Token `
        -Body @{ user_id = $adminUserId; full_name = 'W1 live proof ADMIN'; role = 'ADMIN' } `
        -Headers @{ Prefer = 'return=representation' }
    $adminInsertDenied = -not (Test-Success $response.Status)
    Add-Result 'R06 buyer cannot insert an ADMIN user' 'non-2xx' $response.Status `
        $adminInsertDenied $response.Body

    $response = Invoke-Supabase -Method GET `
        -Path "/rest/v1/users?select=user_id&user_id=eq.$adminUserId" -Token $buyer.Token
    $rows = Get-JsonArray $response.Body
    $adminRowAbsent = (Test-Success $response.Status) -and $rows.Count -eq 0
    Add-Result 'R07 ADMIN row was not created' '0 rows' "$($response.Status) / $($rows.Count) rows" `
        $adminRowAbsent $(if (-not $adminRowAbsent) { "Check/delete user_id $adminUserId" } else { '' })

    $response = Invoke-Supabase -Method GET `
        -Path '/rest/v1/admin_kyc_queue?select=*' -Token $buyer.Token
    $rows = Get-JsonArray $response.Body
    $buyerKycEmpty = (Test-Success $response.Status) -and $rows.Count -eq 0
    Add-Result 'R08 buyer gets zero admin KYC rows' '200 / 0 rows' "$($response.Status) / $($rows.Count) rows" `
        $buyerKycEmpty $response.Body

    $response = Invoke-Supabase -Method GET `
        -Path '/rest/v1/store_profiles?select=contact_number&limit=1' -Token $buyer.Token
    Add-Result 'R09 buyer cannot read store contact_number' 'non-2xx' $response.Status `
        (-not (Test-Success $response.Status)) $response.Body

    $buyerProductId = [guid]::NewGuid().ToString()
    $response = Invoke-Supabase -Method POST `
        -Path '/rest/v1/products' -Token $buyer.Token `
        -Body @{
            product_id = $buyerProductId
            seller_id = $buyer.UserId
            category_id = 4
            name = 'W1 LIVE PROOF BUYER'
            description = 'rollback only'
            base_price = 1.00
            status = 'ACTIVE'
            is_preorder_enabled = $false
            category_attributes = @()
        } -Headers @{ Prefer = 'return=representation' }
    Add-Result 'R10 buyer cannot insert a product' 'non-2xx' $response.Status `
        (-not (Test-Success $response.Status)) $(if (Test-Success $response.Status) { "Delete product_id $buyerProductId via SQL editor" } else { $response.Body })

    # ------------------------------------------------------------------------
    # Anon
    # ------------------------------------------------------------------------
    $response = Invoke-Supabase -Method GET `
        -Path '/rest/v1/users?select=email&limit=1' -Token $AnonKey
    Add-Result 'R11 anon cannot read users.email' 'non-2xx' $response.Status `
        (-not (Test-Success $response.Status)) $response.Body

    $response = Invoke-Supabase -Method GET `
        -Path '/rest/v1/public_profiles?select=user_id,full_name,is_seller_verified,last_seen_at&limit=1' `
        -Token $AnonKey
    Add-Result 'R12 anon cannot read public_profiles' 'non-2xx' $response.Status `
        (-not (Test-Success $response.Status)) $response.Body

    $response = Invoke-Supabase -Method GET `
        -Path '/rest/v1/public_profiles?select=user_id,full_name,is_seller_verified,last_seen_at&limit=1' `
        -Token $buyer.Token
    $rows = Get-JsonArray $response.Body
    Add-Result 'R12b buyer can read public_profiles' '200' "$($response.Status) / $($rows.Count) rows" `
        ((Test-Success $response.Status) -and $rows.Count -ge 1) $response.Body

    $response = Invoke-Supabase -Method GET `
        -Path '/rest/v1/public_store_profiles?select=*&limit=1' -Token $AnonKey
    $rows = Get-JsonArray $response.Body
    Add-Result 'R13 anon can read public_store_profiles' '200' "$($response.Status) / $($rows.Count) rows" `
        (Test-Success $response.Status) $response.Body

    $response = Invoke-Supabase -Method GET `
        -Path '/rest/v1/my_profile?select=*' -Token $AnonKey
    Add-Result 'R14 anon cannot read my_profile' 'non-2xx' $response.Status `
        (-not (Test-Success $response.Status)) $response.Body

    $response = Invoke-Supabase -Method GET `
        -Path '/rest/v1/admin_kyc_queue?select=*' -Token $AnonKey
    Add-Result 'R15 anon cannot read admin_kyc_queue' 'non-2xx' $response.Status `
        (-not (Test-Success $response.Status)) $response.Body

    $response = Invoke-Supabase -Method GET `
        -Path '/rest/v1/my_store_profile?select=*' -Token $AnonKey
    Add-Result 'R16 anon cannot read my_store_profile' 'non-2xx' $response.Status `
        (-not (Test-Success $response.Status)) $response.Body

    # ------------------------------------------------------------------------
    # Approved seller: legitimate product insert, then delete
    # ------------------------------------------------------------------------
    $seller = Get-Token 'approved seller' $sellerEmail $sellerPassword
    Add-Result 'R17 approved seller login' '200' '200' $true 'real Supabase Auth session'

    $sellerProductId = [guid]::NewGuid().ToString()
    $response = Invoke-Supabase -Method POST `
        -Path '/rest/v1/products' -Token $seller.Token `
        -Body @{
            product_id = $sellerProductId
            seller_id = $seller.UserId
            category_id = 4
            name = "W1 LIVE PROOF SELLER $sellerProductId"
            description = 'rollback only'
            base_price = 1.00
            status = 'ACTIVE'
            is_preorder_enabled = $false
            category_attributes = @()
        } -Headers @{ Prefer = 'return=representation' }
    $rows = Get-JsonArray $response.Body
    $sellerInsert = (Test-Success $response.Status) -and $rows.Count -eq 1
    Add-Result 'R18 approved seller can insert a product' '2xx / 1 row' "$($response.Status) / $($rows.Count) rows" `
        $sellerInsert $response.Body

    if ($sellerInsert) {
        $response = Invoke-Supabase -Method DELETE `
            -Path "/rest/v1/products?product_id=eq.$sellerProductId" -Token $seller.Token
        Add-Result 'R19 seller cleanup deletes the proof product' '2xx' $response.Status `
            (Test-Success $response.Status) $(if (-not (Test-Success $response.Status)) { "Delete product_id $sellerProductId manually" } else { '' })
    }

    # ------------------------------------------------------------------------
    # Pending seller: product insert must fail
    # ------------------------------------------------------------------------
    $pending = Get-Token 'pending seller' $pendingEmail $pendingPassword
    Add-Result 'R20 pending seller login' '200' '200' $true 'real Supabase Auth session'

    $pendingProductId = [guid]::NewGuid().ToString()
    $response = Invoke-Supabase -Method POST `
        -Path '/rest/v1/products' -Token $pending.Token `
        -Body @{
            product_id = $pendingProductId
            seller_id = $pending.UserId
            category_id = 4
            name = 'W1 LIVE PROOF PENDING'
            description = 'rollback only'
            base_price = 1.00
            status = 'ACTIVE'
            is_preorder_enabled = $false
            category_attributes = @()
        } -Headers @{ Prefer = 'return=representation' }
    Add-Result 'R21 pending seller cannot insert a product' 'non-2xx' $response.Status `
        (-not (Test-Success $response.Status)) $(if (Test-Success $response.Status) { "Delete product_id $pendingProductId via SQL editor" } else { $response.Body })

    # ------------------------------------------------------------------------
    # Pending seller: cannot self-approve KYC
    # ------------------------------------------------------------------------
    $response = Invoke-Supabase -Method GET `
        -Path "/rest/v1/seller_verifications?select=id,status&user_id=eq.$($pending.UserId)" -Token $pending.Token
    $rows = Get-JsonArray $response.Body
    $hasVerification = (Test-Success $response.Status) -and $rows.Count -ge 1

    if ($hasVerification) {
        $response = Invoke-Supabase -Method PATCH `
            -Path "/rest/v1/seller_verifications?user_id=eq.$($pending.UserId)" -Token $pending.Token `
            -Body @{ status = 'APPROVED' } -Headers @{ Prefer = 'return=representation' }
        $selfApproveDenied = -not (Test-Success $response.Status)
        Add-Result 'R22 pending seller cannot self-approve KYC' 'non-2xx' $response.Status `
            $selfApproveDenied $response.Body

        $response = Invoke-Supabase -Method GET `
            -Path "/rest/v1/seller_verifications?select=id,status&user_id=eq.$($pending.UserId)" -Token $pending.Token
        $rows = Get-JsonArray $response.Body
        $approved = @($rows | Where-Object { $_.status -eq 'APPROVED' }).Count
        $statusUnchanged = (Test-Success $response.Status) -and $approved -eq 0
        Add-Result 'R23 pending seller KYC status unchanged' 'not APPROVED' "$($response.Status) / approved=$approved" `
            $statusUnchanged $response.Body
    } else {
        Add-Result 'R22 pending seller KYC row exists' '>= 1 row' "$($response.Status) / $($rows.Count) rows" `
            $false 'Choose a pending seller with a verification row'
    }

    # ------------------------------------------------------------------------
    # Admin: KYC queue is readable
    # ------------------------------------------------------------------------
    if (-not [string]::IsNullOrWhiteSpace($adminEmail) -and -not [string]::IsNullOrWhiteSpace($adminPassword)) {
        $admin = Get-Token 'admin' $adminEmail $adminPassword
        Add-Result 'R24 admin login' '200' '200' $true 'real Supabase Auth session'

        $response = Invoke-Supabase -Method GET `
            -Path '/rest/v1/admin_kyc_queue?select=*' -Token $admin.Token
        $rows = Get-JsonArray $response.Body
        Add-Result 'R25 admin can read admin_kyc_queue' '200' "$($response.Status) / $($rows.Count) rows" `
            (Test-Success $response.Status) $response.Body
    } else {
        Add-Result 'R24-R25 admin KYC queue' '200' 'SKIPPED' $true 'Set W1_ADMIN_EMAIL / W1_ADMIN_PASSWORD to run'
    }
}
finally {
    $client.Dispose()
}

$script:results | Format-Table -AutoSize -Wrap
$failures = @($script:results | Where-Object { $_.Status -eq 'FAIL' })
if ($failures.Count -gt 0) {
    Write-Host "`nW1 LIVE PROOF: FAIL ($($failures.Count) row(s))" -ForegroundColor Red
    exit 1
}
Write-Host "`nW1 LIVE PROOF: PASS ($($script:results.Count) rows)" -ForegroundColor Green
