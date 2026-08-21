import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Profile tab — Android `ProfileScreen` parity (spec-secondary-screens.md §3).
///
/// Layout: identity card (display name / plan chip / anonymous account-number
/// row / connection status) → subscription summary card → ACCOUNT section
/// (Privacy Policy, Terms) → SESSION section (Sign out, Delete Account).
///
/// Deliberate iOS deviations from Android (platform constraints):
///   - NO "Redeem voucher" row — voucher redemption is an App Store 3.1.1
///     rejection vector (spec-platform-constraints.md §2.4).
///   - NO "Manage on web" row — mirrors the store-build treatment (Android
///     hides it when IS_PLAY_BUILD; anti-steering).
///
/// Tab-root screen: transparent background over the app-root PixelCanvas.
struct ProfileView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @EnvironmentObject var vpnVM: VpnViewModel
    @Environment(\.openURL) private var openURL

    @State private var showSubscription = false
    @State private var showDeleteDialog = false
    @State private var deletePassword = ""
    /// Local empty-password guard message ("Please enter your password") —
    /// applied ONLY when the account has a password. Server-side results
    /// arrive via `authVM.deleteError`.
    @State private var localDeleteError: String?
    @State private var showCopiedToast = false
    @State private var copyToastTask: Task<Void, Never>?

    /// SSO / password-less anonymous accounts must NOT be asked for a
    /// password — the backend accepts a password-less delete (GDPR Art. 17).
    /// Defaults true (fail-safe: ask rather than let a password account
    /// delete without one).
    private var requiresPassword: Bool { authVM.user?.hasPassword ?? true }

    /// Signed out. The POLICIES stay exactly where they are — they are not
    /// account based and a signed-out user must be able to read them (5.1.1(v)
    /// / 5.1.1(i)). Identity, plan and the session actions are replaced by a
    /// single honest sign-in card rather than empty scaffolding.
    private var isGuest: Bool { authVM.isGuest }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                if isGuest {
                    guestCard
                } else {
                    identityCard
                    subscriptionCard
                }

                sectionLabel("Account")
                    .padding(.top, 4)
                ProfileActionRow(
                    icon: "checkmark.shield",
                    title: "Privacy Policy",
                    subtitle: "birdo.app/privacy"
                ) {
                    openURL(URL(string: "https://birdo.app/privacy")!)
                }
                ProfileActionRow(
                    icon: "doc.text",
                    title: "Terms of Service",
                    subtitle: "birdo.app/terms"
                ) {
                    openURL(URL(string: "https://birdo.app/terms")!)
                }

                if !isGuest {
                    sectionLabel("Session")
                        .padding(.top, 8)
                    ProfileActionRow(
                        icon: "rectangle.portrait.and.arrow.right",
                        title: "Sign out",
                        subtitle: signOutSubtitle,
                        destructive: true
                    ) {
                        signOut()
                    }
                    ProfileActionRow(
                        icon: "trash",
                        title: "Delete Account",
                        subtitle: "Permanently delete your account and data",
                        destructive: true
                    ) {
                        showDeleteDialog = true
                    }
                }

                Spacer(minLength: 40)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 24)
        }
        .scrollIndicators(.hidden)
        .modifier(HideNavigationBar())
        .navigationDestination(isPresented: $showSubscription) {
            SubscriptionView()
        }
        .overlay {
            if showDeleteDialog {
                deleteDialog
                    .transition(.opacity)
            }
        }
        .animation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick),
                   value: showDeleteDialog)
        .overlay(alignment: .bottom) {
            if showCopiedToast {
                copiedToast
            }
        }
        .task {
            // Tab focus: refetch identity + plan (30 s stats cache keeps this
            // cheap — spec-secondary-screens.md warning 11). Both are no-ops
            // without a session, so the guest card never triggers a request.
            guard !isGuest else { return }
            vpnVM.refreshSubscription()
            await authVM.refreshProfile()
        }
        .onDisappear {
            copyToastTask?.cancel()
        }
    }

    // MARK: - Guest card

    private var guestCard: some View {
        BirdoCard(cornerRadius: 22, horizontalPadding: 20, verticalPadding: 20) {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    ZStack {
                        Circle().fill(BirdoTheme.accentA(0.14)).frame(width: 44, height: 44)
                        Image(systemName: "person.crop.circle")
                            .font(.system(size: 22))
                            .foregroundStyle(BirdoTheme.accent)
                    }
                    .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Not signed in")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(BirdoTheme.onBackground)
                        Text("Using Birdo VPN without an account")
                            .font(.system(size: 13))
                            .foregroundStyle(BirdoTheme.onSurfaceMuted)
                    }
                }

                Text("An account is what lets the server create your private "
                     + "WireGuard key and hold a connection slot. Settings, VPN "
                     + "settings, the locations list and the policies below all "
                     + "work without one.")
                    .font(.system(size: 13))
                    .foregroundStyle(BirdoTheme.onSurfaceMuted)
                    .fixedSize(horizontal: false, vertical: true)

                PrimaryButton("Sign in or create an account", variant: .brand) {
                    authVM.requestSignIn(.profile)
                }
                .accessibilityIdentifier("profile_sign_in")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: - Identity card (§3.1)

    private var identityCard: some View {
        BirdoCard(cornerRadius: 22, horizontalPadding: 20, verticalPadding: 20) {
            VStack(spacing: 14) {
                // Header: name/email left, plan chip right. Long emails and
                // 24-digit ids truncate so the chip never leaves the screen.
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(displayName)
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(BirdoTheme.onBackground)
                            .lineLimit(1)
                        if !authVM.isAnonymousAccount, let email = authVM.userEmail, !email.isEmpty {
                            Text(email)
                                .font(.system(size: 13))
                                .foregroundStyle(BirdoTheme.onSurfaceMuted)
                                .lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    PlanBadge(vpnVM.currentPlan)
                }

                // Anonymous account number — the account's ONLY credential, so
                // it must be copyable (the display truncates; the clipboard
                // gets the full id).
                if authVM.isAnonymousAccount, let number = authVM.anonymousAccountNumber {
                    accountNumberRow(number)
                }

                connectionStatusRow
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func accountNumberRow(_ number: String) -> some View {
        Button {
            copyAccountNumber(number)
        } label: {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 1) {
                    Text("ACCOUNT NUMBER")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(BirdoTheme.onSurfaceFaint)
                    Text(number)
                        .font(BirdoTheme.Fonts.mono)
                        .foregroundStyle(BirdoTheme.onBackground)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "doc.on.doc")
                    .font(.system(size: 18))
                    .foregroundStyle(BirdoTheme.accent)
                    .padding(8)
            }
            .padding(.leading, 14)
            .padding(.trailing, 6)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(BirdoTheme.surfaceRaised)
            )
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityLabel("Account number \(number)")
        .accessibilityHint("Copies the full account number")
    }

    private var connectionStatusRow: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(vpnVM.isConnected ? BirdoTheme.green : BirdoTheme.onSurfaceFaint)
                .frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 1) {
                Text(vpnVM.isConnected ? "Protected" : "Not connected")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(BirdoTheme.onBackground)
                Text(connectionDetail)
                    .font(.system(size: 11))
                    .foregroundStyle(BirdoTheme.onSurfaceMuted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "shield")
                .font(.system(size: 20))
                .foregroundStyle(vpnVM.isConnected ? BirdoTheme.green : BirdoTheme.onSurfaceFaint)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(BirdoTheme.surfaceRaised)
        )
        .accessibilityElement(children: .combine)
    }

    // MARK: - Subscription summary card (§3.2)

    private var subscriptionCard: some View {
        let subscription = vpnVM.subscription
        let plan = vpnVM.currentPlan
        // Android parity: ACTIVE is a case-insensitive match on the raw
        // status string; nil subscription reads as inactive.
        let isActive = (subscription?.status ?? "INACTIVE")
            .caseInsensitiveCompare("ACTIVE") == .orderedSame
        let endsAt = Self.formatRenewalDate(subscription?.subscriptionEndsAt)

        return BirdoCard(cornerRadius: 20, horizontalPadding: 18, verticalPadding: 18) {
            VStack(spacing: 14) {
                HStack(spacing: 14) {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(BirdoTheme.planGradient(plan))
                        .frame(width: 48, height: 48)
                        .overlay(
                            Image(systemName: "star")
                                .font(.system(size: 22, weight: .medium))
                                .foregroundStyle(.white)
                        )
                    VStack(alignment: .leading, spacing: 2) {
                        Text(BirdoTheme.planDisplayName(plan))
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(BirdoTheme.onBackground)
                        Text(subscriptionStatusLine(isActive: isActive, endsAt: endsAt))
                            .font(.system(size: 12))
                            .foregroundStyle(BirdoTheme.onSurfaceMuted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    statusPill(active: isActive)
                }

                if let subscription {
                    HStack(spacing: 8) {
                        benefitChip(subscription.maxConnections == 1
                                    ? "1 device"
                                    : "\(subscription.maxConnections) devices")
                        benefitChip(subscription.bandwidthLimitGb > 0
                                    ? "\(Self.wholeGb(subscription.bandwidthLimitGb)) GB / month"
                                    : "Unlimited data")
                        if subscription.hasPremiumServers {
                            benefitChip("Premium servers")
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                // CTA — refresh plan truth, then push the (informational)
                // Subscription screen.
                Button {
                    vpnVM.refreshSubscription()
                    showSubscription = true
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "creditcard")
                            .font(.system(size: 18))
                        Text(isActive ? "Manage subscription" : "Upgrade plan")
                            .font(.system(size: 14, weight: .semibold))
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 14, weight: .semibold))
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .background(
                        RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                            .fill(BirdoTheme.planGradient(plan))
                    )
                }
                .buttonStyle(PressScaleButtonStyle())
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func subscriptionStatusLine(isActive: Bool, endsAt: String?) -> String {
        switch (endsAt, isActive) {
        case (let date?, true):
            return "Renews \(date)"
        case (let date?, false):
            // Canceled / expiring: the date is when access ENDS — calling
            // that "Renews" would be a lie.
            return "Access until \(date)"
        case (nil, true):
            return "Active subscription"
        case (nil, false):
            return "Free tier — upgrade for premium"
        }
    }

    private func statusPill(active: Bool) -> some View {
        Text(active ? "ACTIVE" : "INACTIVE")
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(active ? BirdoTheme.green : BirdoTheme.onSurfaceMuted)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(
                Capsule().fill(active ? BirdoTheme.green.opacity(0.18) : BirdoTheme.surfaceRaised)
            )
    }

    private func benefitChip(_ label: String) -> some View {
        Text(label)
            .font(.system(size: 11, weight: .medium))
            .foregroundStyle(BirdoTheme.onSurfaceMuted)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: BirdoTheme.Radius.sm, style: .continuous)
                    .fill(BirdoTheme.surfaceRaised)
            )
            .overlay(
                RoundedRectangle(cornerRadius: BirdoTheme.Radius.sm, style: .continuous)
                    .strokeBorder(BirdoTheme.hairlineSoft, lineWidth: 1)
            )
    }

    // MARK: - Delete dialog (§3.5)

    private var deleteDialog: some View {
        BirdoConfirmDialog(
            title: "Delete Account",
            message: requiresPassword
                ? "This action is permanent and cannot be undone. All your data, VPN keys, and subscription will be deleted. Enter your password to confirm."
                : "This action is permanent and cannot be undone. All your data, VPN keys, and subscription will be deleted.",
            icon: "exclamationmark.triangle.fill",
            iconColor: BirdoTheme.red,
            confirmLabel: "Delete My Account",
            confirmIsDestructive: true,
            cancelLabel: "Cancel",
            isWorking: authVM.isDeleting,
            onConfirm: confirmDelete,
            onCancel: dismissDeleteDialog
        ) {
            VStack(spacing: 0) {
                if requiresPassword {
                    BirdoTextField("Password",
                                   placeholder: "Password",
                                   text: $deletePassword,
                                   isSecure: true,
                                   error: authVM.deleteError ?? localDeleteError,
                                   textContentType: .password)
                        .disabled(authVM.isDeleting)
                        .onChange(of: deletePassword) { _, _ in
                            localDeleteError = nil
                        }
                } else if let err = authVM.deleteError {
                    Text(err)
                        .font(BirdoTheme.Fonts.bodySmall)
                        .foregroundStyle(BirdoTheme.red)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(.top, 4)
        }
    }

    private func confirmDelete() {
        // Local guard ONLY for password accounts — the Android bug ran this
        // check for password-less accounts too and stranded their deletes
        // (spec-secondary-screens.md warning 1). Do not replicate.
        if requiresPassword && deletePassword.isEmpty {
            localDeleteError = "Please enter your password"
            return
        }
        localDeleteError = nil
        // Tunnel down BEFORE erasure — deletion wipes the shared keychain the
        // tunnel extension reads (same ordering rule as logout).
        vpnVM.disconnect()
        authVM.deleteAccount(password: requiresPassword ? deletePassword : nil)
    }

    private func dismissDeleteDialog() {
        guard !authVM.isDeleting else { return }
        showDeleteDialog = false
        deletePassword = ""
        localDeleteError = nil
        authVM.deleteError = nil
    }

    // MARK: - Actions

    private func signOut() {
        // No confirmation dialog (Android parity). Slot release is awaited so
        // the server frees this device's connection before the tokens die.
        Task {
            await vpnVM.disconnectForSignOut()
            authVM.logout()
        }
    }

    private func copyAccountNumber(_ number: String) {
        Clipboard.copySensitive(number)
        Announce.message("Account number copied")
        withAnimation(BirdoTheme.Motion.decel()) {
            showCopiedToast = true
        }
        copyToastTask?.cancel()
        copyToastTask = Task {
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            withAnimation(BirdoTheme.Motion.accel()) {
                showCopiedToast = false
            }
        }
    }

    // MARK: - Derived copy

    /// user.name → "Anonymous account" → email local-part → "Account".
    private var displayName: String {
        if let name = authVM.user?.name,
           !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return name
        }
        if authVM.isAnonymousAccount { return "Anonymous account" }
        if let email = authVM.userEmail, !email.isEmpty {
            return String(email.prefix(while: { $0 != "@" }))
        }
        return "Account"
    }

    private var signOutSubtitle: String {
        if let email = authVM.userEmail, !email.isEmpty { return email }
        if authVM.isAnonymousAccount { return "Anonymous account" }
        return "Sign out of this device"
    }

    /// Android shows "Public IP · {ip}" here, polled from the running VPN
    /// service — iOS has no public-IP plumbing, so the connected state names
    /// the selected server instead of showing a stale prompt.
    private var connectionDetail: String {
        if vpnVM.isConnected {
            if let server = vpnVM.selectedServer { return "Connected · \(server.name)" }
            return "Connected"
        }
        return "Tap Connect to start"
    }

    // MARK: - Small pieces

    /// Plain uppercase section label (Android `SectionLabel` — deliberately
    /// NOT the tick-bar SectionHeader used on Settings).
    private func sectionLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .font(BirdoTheme.Fonts.sectionHeader)
            .tracking(1.5)
            .foregroundStyle(BirdoTheme.onSurfaceMuted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, 4)
            .accessibilityAddTraits(.isHeader)
    }

    private var copiedToast: some View {
        Text("Account number copied")
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(BirdoTheme.onSurface)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Capsule().fill(BirdoTheme.surfaceElevated))
            .overlay(Capsule().strokeBorder(BirdoTheme.hairline, lineWidth: 1))
            .padding(.bottom, 24)
            .transition(.opacity.combined(with: .move(edge: .bottom)))
    }

    private static func wholeGb(_ value: Double) -> String {
        value.truncatingRemainder(dividingBy: 1) == 0
            ? String(Int(value))
            : String(format: "%.1f", value)
    }

    /// ISO instant / plain date → "MMM d, yyyy" in the device timezone;
    /// nil / parse failure → nil (Android `formatRenewalDate`).
    private static func formatRenewalDate(_ raw: String?) -> String? {
        guard let raw = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty else { return nil }
        guard let date = parseInstantOrDate(raw) else { return nil }
        let formatter = DateFormatter()
        // Fixed-format string ⇒ fixed locale + Gregorian calendar, or users on
        // non-Gregorian device calendars see a wrong date (twin of LimitView).
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.dateFormat = "MMM d, yyyy"
        return formatter.string(from: date)
    }

    private static func parseInstantOrDate(_ raw: String) -> Date? {
        let withFractional = ISO8601DateFormatter()
        withFractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = withFractional.date(from: raw) { return date }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        if let date = plain.date(from: raw) { return date }
        // Plain "yyyy-MM-dd" (or a date-time whose time part failed above).
        let dayOnly = DateFormatter()
        dayOnly.locale = Locale(identifier: "en_US_POSIX")
        dayOnly.dateFormat = "yyyy-MM-dd"
        return dayOnly.date(from: String(raw.prefix(while: { $0 != "T" })))
    }
}

// MARK: - Action row

/// Tappable settings-style row: 36pt icon chip, title + subtitle, trailing
/// chevron. Destructive rows go red (icon + title), matching Android.
private struct ProfileActionRow: View {
    let icon: String
    let title: String
    let subtitle: String?
    var destructive: Bool = false
    let action: () -> Void

    init(icon: String,
         title: String,
         subtitle: String?,
         destructive: Bool = false,
         action: @escaping () -> Void) {
        self.icon = icon
        self.title = title
        self.subtitle = subtitle
        self.destructive = destructive
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            BirdoCard(horizontalPadding: 14, verticalPadding: 14) {
                HStack(spacing: 12) {
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.sm, style: .continuous)
                        .fill(BirdoTheme.surfaceRaised)
                        .frame(width: 36, height: 36)
                        .overlay(
                            RoundedRectangle(cornerRadius: BirdoTheme.Radius.sm, style: .continuous)
                                .strokeBorder(BirdoTheme.hairlineSoft, lineWidth: 1)
                        )
                        .overlay(
                            Image(systemName: icon)
                                .font(.system(size: 18))
                                .foregroundStyle(destructive ? BirdoTheme.red : BirdoTheme.accent)
                        )
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(destructive ? BirdoTheme.red : BirdoTheme.onBackground)
                        if let subtitle {
                            Text(subtitle)
                                .font(.system(size: 12))
                                .foregroundStyle(BirdoTheme.onSurfaceMuted)
                                .lineLimit(2)
                                .multilineTextAlignment(.leading)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(BirdoTheme.onSurfaceFaint)
                }
            }
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityElement(children: .combine)
    }
}
