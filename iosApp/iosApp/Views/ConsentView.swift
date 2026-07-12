import SwiftUI

/// GDPR consent screen — must be accepted before using the app.
struct ConsentView: View {
    let onAccept: () -> Void
    let onDecline: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Spacer().frame(height: 48)

                Image(systemName: "shield.fill")
                    .font(.system(size: 56))
                    .foregroundColor(BirdoTheme.accent)

                Text("Privacy First")
                    .font(.title.bold())
                    .foregroundColor(.white)

                Text("Birdo VPN operates a strict zero-logs policy on RAM-only volatile infrastructure. No VPN usage data is stored. We collect only account-level data necessary to operate the service.")
                    .font(.subheadline)
                    .foregroundColor(BirdoTheme.white60)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                VStack(alignment: .leading, spacing: 16) {
                    DataItem(title: "No Activity Logs", description: "Zero-logs policy on RAM-only volatile infrastructure. No browsing, traffic, DNS, timestamps, or IPs logged.")
                    DataItem(title: "Account Data Only", description: "Only email and basic account info stored in a separate database — never on VPN servers.")
                    DataItem(title: "Crash Reports", description: "Anonymous crash data helps us fix bugs (opt-out available).")
                    DataItem(title: "No Data Sales", description: "Your data is never sold, shared, or monetized.")
                }
                .padding(20)
                .background(BirdoTheme.surface)
                .clipShape(RoundedRectangle(cornerRadius: 16))

                Link("Read Full Privacy Policy", destination: URL(string: "https://birdo.app/privacy")!)
                    .font(.subheadline)
                    .foregroundColor(BirdoTheme.accentSoft)

                Button(action: onAccept) {
                    Text("I Agree")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                }
                .buttonStyle(.borderedProminent)
                .tint(BirdoTheme.accent)
                .clipShape(RoundedRectangle(cornerRadius: 12))

                Button(action: onDecline) {
                    Text("Decline")
                        .foregroundColor(BirdoTheme.white40)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(BirdoTheme.white20, lineWidth: 1)
                )

                Text("Consent is required to use the VPN service.")
                    .font(.caption2)
                    .foregroundColor(BirdoTheme.white20)

                Spacer().frame(height: 32)
            }
            .padding(.horizontal, 24)
        }
        .background(BirdoTheme.black)
    }
}

private struct DataItem: View {
    let title: String
    let description: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(.white)
            Text(description)
                .font(.caption)
                .foregroundColor(BirdoTheme.white60)
        }
    }
}
