import SwiftUI
import UIKit

/// The outlined Birdo text field: label above (13pt medium 60%-white), field
/// radius 12 on a 4%-white container, focus border emerald-300 @60%, secure
/// variant with an eye toggle (a11y "Show password"/"Hide password"), error
/// border + red supporting text.
/// Spec: spec-auth-flow.md §1 (shared field style), spec-secondary-screens.md
/// §0.3 (BirdoTextField).
///
/// Focus chaining: attach the parent's own `.focused($field, equals: .x)` to
/// the component — multiple focus bindings on the same field stay in sync;
/// the internal one only drives the focus styling.
struct BirdoTextField: View {
    let label: String?
    let placeholder: String
    @Binding var text: String
    var isSecure: Bool
    var error: String?
    var supportingText: String?
    var keyboardType: UIKeyboardType
    var textContentType: UITextContentType?
    var submitLabel: SubmitLabel
    var autocapitalization: TextInputAutocapitalization
    var monospaced: Bool
    var onSubmit: (() -> Void)?

    @State private var revealed = false
    @FocusState private var focused: Bool

    init(_ label: String? = nil,
         placeholder: String,
         text: Binding<String>,
         isSecure: Bool = false,
         error: String? = nil,
         supportingText: String? = nil,
         keyboardType: UIKeyboardType = .default,
         textContentType: UITextContentType? = nil,
         submitLabel: SubmitLabel = .done,
         autocapitalization: TextInputAutocapitalization = .never,
         monospaced: Bool = false,
         onSubmit: (() -> Void)? = nil) {
        self.label = label
        self.placeholder = placeholder
        self._text = text
        self.isSecure = isSecure
        self.error = error
        self.supportingText = supportingText
        self.keyboardType = keyboardType
        self.textContentType = textContentType
        self.submitLabel = submitLabel
        self.autocapitalization = autocapitalization
        self.monospaced = monospaced
        self.onSubmit = onSubmit
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let label {
                Text(label)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(BirdoTheme.white60)
                    .padding(.leading, 4)
                    .padding(.bottom, 6)
            }

            HStack(spacing: 8) {
                field
                    .focused($focused)
                    .font(monospaced ? BirdoTheme.Fonts.mono : BirdoTheme.Fonts.bodyMedium)
                    .foregroundStyle(focused ? BirdoTheme.white : BirdoTheme.white80)
                    .tint(BirdoTheme.accentSoft)
                    .keyboardType(keyboardType)
                    .textContentType(textContentType)
                    .submitLabel(submitLabel)
                    .textInputAutocapitalization(autocapitalization)
                    .autocorrectionDisabled(true)
                    .onSubmit { self.onSubmit?() }

                if isSecure {
                    Button {
                        revealed.toggle()
                        focused = true
                    } label: {
                        Image(systemName: revealed ? "eye.slash" : "eye")
                            .font(.system(size: 16))
                            .foregroundStyle(BirdoTheme.white40)
                    }
                    .accessibilityLabel(revealed ? "Hide password" : "Show password")
                }
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 48)
            .background(
                RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                    .fill(focused ? Color.white.opacity(0.07) : BirdoTheme.glassInput)
            )
            .overlay(
                RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                    .strokeBorder(borderColor, lineWidth: 1)
            )
            .animation(.easeInOut(duration: 0.2), value: focused)

            if let supporting = error ?? supportingText {
                Text(supporting)
                    .font(BirdoTheme.Fonts.bodySmall)
                    .foregroundStyle(error != nil ? BirdoTheme.red : BirdoTheme.white60)
                    .padding(.leading, 4)
                    .padding(.top, 6)
            }
        }
    }

    @ViewBuilder
    private var field: some View {
        if isSecure && !revealed {
            SecureField("", text: $text, prompt: prompt)
        } else {
            TextField("", text: $text, prompt: prompt)
        }
    }

    private var prompt: Text {
        Text(placeholder).foregroundStyle(BirdoTheme.white40)
    }

    private var borderColor: Color {
        if error != nil { return BirdoTheme.red }
        if focused { return BirdoTheme.accentSoft.opacity(0.6) }
        return BirdoTheme.hairlineSoft
    }
}
