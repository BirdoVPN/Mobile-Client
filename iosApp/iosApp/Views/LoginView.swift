import SwiftUI

/// Login screen with email/password, 2FA, and anonymous login support.
struct LoginView: View {
    @EnvironmentObject var authVM: AuthViewModel

    @State private var email = ""
    @State private var password = ""
    @State private var twoFactorCode = ""
    @FocusState private var focusedField: Field?

    private enum Field { case email, password, twoFactor }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Spacer().frame(height: 60)

                // Logo
                Image(systemName: "bird.fill")
                    .font(.system(size: 48))
                    .foregroundColor(BirdoTheme.purple)

                Text("Birdo VPN")
                    .font(.title.bold())
                    .foregroundColor(.white)

                Text("Sign in to connect")
                    .font(.subheadline)
                    .foregroundColor(BirdoTheme.white60)

                Spacer().frame(height: 16)

                if authVM.requiresTwoFactor {
                    twoFactorSection
                } else {
                    loginSection
                }

                // Error
                if let error = authVM.error {
                    HStack(spacing: 8) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(BirdoTheme.red)
                            .font(.caption)
                        Text(error)
                            .font(.caption)
                            .foregroundColor(BirdoTheme.red)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(BirdoTheme.redBg)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                }

                Spacer()
            }
            .padding(.horizontal, 32)
        }
        .background(BirdoTheme.black)
    }

    // MARK: - Login Section

    private var loginSection: some View {
        VStack(spacing: 14) {
            TextField("Email", text: $email)
                .textContentType(.emailAddress)
                .keyboardType(.emailAddress)
                .autocapitalization(.none)
                .focused($focusedField, equals: .email)
                .textFieldStyle(BirdoTextFieldStyle())

            SecureField("Password", text: $password)
                .textContentType(.password)
                .focused($focusedField, equals: .password)
                .textFieldStyle(BirdoTextFieldStyle())

            Button(action: { authVM.login(email: email, password: password) }) {
                if authVM.isLoading {
                    ProgressView().tint(.white)
                } else {
                    Text("Sign In")
                        .font(.headline)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(BirdoTheme.purple)
            .foregroundColor(.white)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .disabled(email.isEmpty || password.isEmpty || authVM.isLoading)

            HStack {
                Button("Sign Up") {
                    if let url = URL(string: "https://birdo.app/login") {
                        UIApplication.shared.open(url)
                    }
                }
                .font(.subheadline)
                .foregroundColor(BirdoTheme.purple)

                Spacer()

                Button("Connect Anonymously") {
                    authVM.loginAnonymous()
                }
                .font(.subheadline)
                .foregroundColor(BirdoTheme.white40)
            }
        }
    }

    // MARK: - 2FA Section

    private var twoFactorSection: some View {
        VStack(spacing: 14) {
            Text("Enter the 6-digit code from your authenticator app")
                .font(.subheadline)
                .foregroundColor(BirdoTheme.white60)
                .multilineTextAlignment(.center)

            TextField("000000", text: $twoFactorCode)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.center)
                .font(.title2.monospaced())
                .focused($focusedField, equals: .twoFactor)
                .textFieldStyle(BirdoTextFieldStyle())
                .onChange(of: twoFactorCode) { _, newValue in
                    twoFactorCode = String(newValue.prefix(6).filter(\.isNumber))
                }

            Button(action: { authVM.verifyTwoFactor(code: twoFactorCode) }) {
                if authVM.isLoading {
                    ProgressView().tint(.white)
                } else {
                    Text("Verify")
                        .font(.headline)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(BirdoTheme.purple)
            .foregroundColor(.white)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .disabled(twoFactorCode.count != 6 || authVM.isLoading)

            Button("Cancel") {
                authVM.cancelTwoFactor()
                twoFactorCode = ""
            }
            .foregroundColor(BirdoTheme.white40)
        }
    }
}

// MARK: - Custom Text Field Style

struct BirdoTextFieldStyle: TextFieldStyle {
    func _body(configuration: TextField<Self._Label>) -> some View {
        configuration
            .padding(14)
            .background(BirdoTheme.surface)
            .foregroundColor(.white)
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(BirdoTheme.white10, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}
