import SwiftUI

/// Port forwarding management screen.
struct PortForwardView: View {
    @EnvironmentObject var vpnVM: VpnViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var showCreateSheet = false
    @State private var portText = ""
    @State private var selectedProtocol = "TCP"
    @State private var deleteTarget: PortForwardEntry?

    private let protocols = ["TCP", "UDP", "Both"]

    var body: some View {
        VStack(spacing: 0) {
            // Active forwards list
            if vpnVM.portForwards.isEmpty {
                emptyState
            } else {
                List {
                    ForEach(vpnVM.portForwards) { entry in
                        portRow(entry)
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    deleteTarget = entry
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                    }
                }
                .scrollContentBackground(.hidden)
            }
        }
        .background(BirdoTheme.black.ignoresSafeArea())
        .navigationTitle("Port Forwarding")
        .onAppear { vpnVM.loadPortForwards() }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showCreateSheet = true
                } label: {
                    Image(systemName: "plus.circle.fill")
                        .foregroundColor(BirdoTheme.accent)
                }
            }
        }
        .sheet(isPresented: $showCreateSheet) {
            createSheet
        }
        .alert("Delete Port Forward?", isPresented: .init(
            get: { deleteTarget != nil },
            set: { if !$0 { deleteTarget = nil } }
        )) {
            Button("Cancel", role: .cancel) { deleteTarget = nil }
            Button("Delete", role: .destructive) {
                if let target = deleteTarget {
                    vpnVM.deletePortForward(id: target.id)
                    deleteTarget = nil
                }
            }
        } message: {
            if let target = deleteTarget {
                Text("Remove port \(target.internalPort) (\(target.proto)) forwarding?")
            }
        }
    }

    // MARK: - Empty State

    private var emptyState: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "network")
                .font(.system(size: 48))
                .foregroundColor(BirdoTheme.white20)
            Text("No Port Forwards")
                .font(.headline)
                .foregroundColor(BirdoTheme.white60)
            Text("Create a port forward to allow inbound connections through the VPN.")
                .font(.caption)
                .foregroundColor(BirdoTheme.white40)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Button {
                showCreateSheet = true
            } label: {
                Label("Create Port Forward", systemImage: "plus")
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(.white)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
                    .background(BirdoTheme.accent)
                    .cornerRadius(12)
            }
            Spacer()
        }
    }

    // MARK: - Port Row

    private func portRow(_ entry: PortForwardEntry) -> some View {
        HStack(spacing: 14) {
            Image(systemName: "arrow.right.circle.fill")
                .font(.title3)
                .foregroundColor(BirdoTheme.blue)

            VStack(alignment: .leading, spacing: 2) {
                Text("Port \(entry.internalPort)")
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(.white)
                // externalPort is non-optional on the canonical model.
                if entry.externalPort > 0 {
                    Text("External: \(entry.externalPort)")
                        .font(.caption)
                        .foregroundColor(BirdoTheme.white60)
                }
            }

            Spacer()

            Text(entry.proto)
                .font(.caption2.weight(.semibold))
                .foregroundColor(BirdoTheme.blue)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(BirdoTheme.blue.opacity(0.15))
                .cornerRadius(6)
        }
        .padding(.vertical, 4)
        .listRowBackground(BirdoTheme.surface)
    }

    // MARK: - Create Sheet

    private var createSheet: some View {
        NavigationStack {
            VStack(spacing: 24) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Internal Port")
                        .font(.caption)
                        .foregroundColor(BirdoTheme.white40)
                    TextField("e.g. 8080", text: $portText)
                        .keyboardType(.numberPad)
                        .textFieldStyle(BirdoTextFieldStyle())
                        .onChange(of: portText) { newValue in
                            portText = String(newValue.filter(\.isNumber).prefix(5))
                        }
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("Protocol")
                        .font(.caption)
                        .foregroundColor(BirdoTheme.white40)
                    Picker("Protocol", selection: $selectedProtocol) {
                        ForEach(protocols, id: \.self) { proto in
                            Text(proto)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Button {
                    guard let port = Int(portText), (1...65535).contains(port) else { return }
                    vpnVM.createPortForward(internalPort: port, proto: selectedProtocol)
                    portText = ""
                    showCreateSheet = false
                } label: {
                    Text("Create")
                        .font(.headline)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(
                            Int(portText).map { (1...65535).contains($0) } == true
                                ? BirdoTheme.accent
                                : BirdoTheme.white10
                        )
                        .cornerRadius(14)
                }
                .disabled(Int(portText).map { (1...65535).contains($0) } != true)

                Spacer()
            }
            .padding()
            .background(BirdoTheme.black.ignoresSafeArea())
            .navigationTitle("New Port Forward")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { showCreateSheet = false }
                        .foregroundColor(BirdoTheme.white60)
                }
            }
        }
    }
}

// MARK: - Model

