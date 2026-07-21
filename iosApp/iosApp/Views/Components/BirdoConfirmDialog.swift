import SwiftUI

/// Styled confirmation dialog: 85%-black scrim, centered card (max ~384pt,
/// radius 16, `surfaceRaised` fill, soft hairline border, padding 24) with a
/// 36pt icon, 18pt semibold title, 14pt 60%-white body, optional extra
/// content (e.g. a password field), and two equal-width buttons. Entrance:
/// scrim fade + dialog scale 0.92→1.
/// Spec: spec-pixelcanvas-design.md §5.4 (logout modal — the canonical
/// styling); used for kill-switch disable, logout, delete-rule, etc.
///
/// Simple confirms: use the `.birdoConfirmDialog(...)` modifier. Dialogs with
/// in-flight state or custom fields: present `BirdoConfirmDialog` directly in
/// an overlay `if` and drive `isWorking` yourself (it blocks dismissal).
struct BirdoConfirmDialog<Extra: View>: View {
    let title: String
    let message: String
    var icon: String
    var iconColor: Color
    var confirmLabel: String
    var confirmIsDestructive: Bool
    var cancelLabel: String
    /// Confirm shows a spinner and both dismiss paths are blocked while true.
    var isWorking: Bool
    var onConfirm: () -> Void
    var onCancel: () -> Void
    @ViewBuilder var extra: () -> Extra

    @State private var appeared = false

    init(title: String,
         message: String,
         icon: String = "exclamationmark.triangle.fill",
         iconColor: Color = BirdoTheme.yellowLight,
         confirmLabel: String,
         confirmIsDestructive: Bool = true,
         cancelLabel: String = "Cancel",
         isWorking: Bool = false,
         onConfirm: @escaping () -> Void,
         onCancel: @escaping () -> Void,
         @ViewBuilder extra: @escaping () -> Extra) {
        self.title = title
        self.message = message
        self.icon = icon
        self.iconColor = iconColor
        self.confirmLabel = confirmLabel
        self.confirmIsDestructive = confirmIsDestructive
        self.cancelLabel = cancelLabel
        self.isWorking = isWorking
        self.onConfirm = onConfirm
        self.onCancel = onCancel
        self.extra = extra
    }

    var body: some View {
        ZStack {
            Color.black.opacity(0.85)
                .ignoresSafeArea()
                .opacity(appeared ? 1 : 0)
                .onTapGesture {
                    if !isWorking { onCancel() }
                }

            VStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 36))
                    .foregroundStyle(iconColor)
                Text(title)
                    .font(BirdoTheme.Fonts.titleLarge)
                    .foregroundStyle(BirdoTheme.onSurface)
                    .multilineTextAlignment(.center)
                Text(message)
                    .font(BirdoTheme.Fonts.bodyMedium)
                    .foregroundStyle(BirdoTheme.white60)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)

                extra()

                HStack(spacing: 10) {
                    dialogButton(cancelLabel,
                                 fill: BirdoTheme.white05,
                                 border: BirdoTheme.hairlineSoft,
                                 textColor: BirdoTheme.onSurface) {
                        if !isWorking { onCancel() }
                    }
                    confirmButton
                }
                .padding(.top, 8)
            }
            .padding(24)
            .frame(maxWidth: 384)
            .background(
                RoundedRectangle(cornerRadius: BirdoTheme.Radius.card, style: .continuous)
                    .fill(BirdoTheme.surfaceRaised)
            )
            .overlay(
                RoundedRectangle(cornerRadius: BirdoTheme.Radius.card, style: .continuous)
                    .strokeBorder(BirdoTheme.hairlineSoft, lineWidth: 1)
            )
            .padding(.horizontal, 24)
            .scaleEffect(appeared ? 1 : 0.92)
            .opacity(appeared ? 1 : 0)
            .accessibilityAddTraits(.isModal)
        }
        .onAppear {
            withAnimation(BirdoTheme.Motion.decel(BirdoTheme.Motion.standard)) {
                appeared = true
            }
        }
    }

    private var confirmButton: some View {
        Button {
            if !isWorking { onConfirm() }
        } label: {
            HStack(spacing: 8) {
                if isWorking {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .tint(confirmIsDestructive ? BirdoTheme.red : .black)
                        .scaleEffect(0.7)
                }
                Text(confirmLabel)
                    .font(.system(size: 14, weight: .medium))
            }
            .foregroundStyle(confirmIsDestructive ? BirdoTheme.red : .black)
            .frame(maxWidth: .infinity, minHeight: 40)
            .background(
                RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                    .fill(confirmIsDestructive ? BirdoTheme.red.opacity(0.18) : Color.white)
            )
            .overlay {
                if confirmIsDestructive {
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                        .strokeBorder(BirdoTheme.red.opacity(0.3), lineWidth: 1)
                }
            }
        }
        .buttonStyle(PressScaleButtonStyle())
        .disabled(isWorking)
    }

    private func dialogButton(_ label: String,
                              fill: Color,
                              border: Color,
                              textColor: Color,
                              action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(textColor)
                .frame(maxWidth: .infinity, minHeight: 40)
                .background(
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                        .fill(fill)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                        .strokeBorder(border, lineWidth: 1)
                )
        }
        .buttonStyle(PressScaleButtonStyle())
        .disabled(isWorking)
    }
}

extension BirdoConfirmDialog where Extra == EmptyView {
    init(title: String,
         message: String,
         icon: String = "exclamationmark.triangle.fill",
         iconColor: Color = BirdoTheme.yellowLight,
         confirmLabel: String,
         confirmIsDestructive: Bool = true,
         cancelLabel: String = "Cancel",
         isWorking: Bool = false,
         onConfirm: @escaping () -> Void,
         onCancel: @escaping () -> Void) {
        self.init(title: title,
                  message: message,
                  icon: icon,
                  iconColor: iconColor,
                  confirmLabel: confirmLabel,
                  confirmIsDestructive: confirmIsDestructive,
                  cancelLabel: cancelLabel,
                  isWorking: isWorking,
                  onConfirm: onConfirm,
                  onCancel: onCancel,
                  extra: { EmptyView() })
    }
}

extension View {
    /// Presents a `BirdoConfirmDialog` over this view. Confirm/cancel both
    /// clear `isPresented` before invoking their callbacks.
    @MainActor
    func birdoConfirmDialog(isPresented: Binding<Bool>,
                            title: String,
                            message: String,
                            icon: String = "exclamationmark.triangle.fill",
                            iconColor: Color = BirdoTheme.yellowLight,
                            confirmLabel: String,
                            confirmIsDestructive: Bool = true,
                            cancelLabel: String = "Cancel",
                            onConfirm: @escaping () -> Void,
                            onCancel: (() -> Void)? = nil) -> some View {
        overlay {
            ZStack {
                if isPresented.wrappedValue {
                    BirdoConfirmDialog(
                        title: title,
                        message: message,
                        icon: icon,
                        iconColor: iconColor,
                        confirmLabel: confirmLabel,
                        confirmIsDestructive: confirmIsDestructive,
                        cancelLabel: cancelLabel,
                        onConfirm: {
                            isPresented.wrappedValue = false
                            onConfirm()
                        },
                        onCancel: {
                            isPresented.wrappedValue = false
                            onCancel?()
                        }
                    )
                    .transition(.opacity)
                }
            }
            .animation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick),
                       value: isPresented.wrappedValue)
        }
    }
}
