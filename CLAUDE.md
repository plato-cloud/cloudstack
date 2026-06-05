<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 -->

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Platoform Fork

This is the **plato-cloud** fork of Apache CloudStack, producing `.deb` packages for the Platoform apt repository at https://apt.platoform.net/. The general CloudStack reference below applies, but the workflow and conventions below take precedence on this fork.

### Branches

| Branch | Channel | Apt distribution |
|---|---|---|
| `4.22` | stable | `noble` |
| `4.22-testing` | testing | `noble-testing` |

Both are forked off `apache/cloudstack:4.22` with one extra commit: the POM version bumped to `4.22.99.0-SNAPSHOT`. The `99` patch number is the platoform marker — see "Version trick" below.

When upstream cuts the next major (4.23), fork `4.23` and `4.23-testing` off `apache/cloudstack:4.23` the same way and bump POM to `4.23.99.0-SNAPSHOT`.

### Promotion flow

```
new commits → 4.22-testing → (validate on us-central1 region) → merge → 4.22 → (rolled out to us-west2)
```

Both branches re-build and re-publish on push.

### Syncing upstream

```sh
git fetch origin
git checkout 4.22-testing
git merge origin/4.22       # pulls in apache's new 4.22.x.y commits
git push plato-cloud 4.22-testing
```

After validation, promote to stable:

```sh
git checkout 4.22
git merge 4.22-testing
git push plato-cloud 4.22
```

### Version trick

POM stays at `4.22.99.0-SNAPSHOT` on both branches. The `99` patch number ensures our packages always outrank any plausible upstream `4.22.x.y` release by Debian version comparison (`99 > x` for any x upstream ships), so apt prefers ours whenever both are seen.

`./packaging/build-deb.sh --brand platoform --use-timestamp` produces packages like:

```
cloudstack-management_4.22.99.0-platoform-1715890000~noble_all.deb
```

The epoch timestamp makes every build uniquely-versioned without manual POM edits between releases. The `~noble` suffix is the Ubuntu codename.

### Remotes

| Remote | URL | Purpose |
|---|---|---|
| `origin` | `https://github.com/apache/cloudstack.git` | Upstream — read-only, never push |
| `plato-cloud` | `git@github.com:plato-cloud/cloudstack.git` | Our fork |

### CI/CD

`.github/workflows/build-deb.yml` runs on push to `4.22` or `4.22-testing`:

1. Builds inside Ubuntu 24.04 with JDK 17 + Maven via `./packaging/build-deb.sh --brand platoform --use-timestamp`
2. Publishes the resulting `.deb` set to S3 via `deb-s3`, scoped to the channel matching the branch name
3. Invalidates CloudFront so clients see the new release immediately

### Required GitHub Actions secrets

| Secret | Value |
|---|---|
| `PLATOFORM_APT_GPG_PRIVATE_KEY` | ASCII-armored private key (from `pulumi stack output --show-secrets gpgPrivateKey` in `platrol-panel/pulumi/aptrepo/`) |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | IAM creds with `s3:PutObject`/`GetObject`/`ListBucket` on bucket `platoform-apt` plus `cloudfront:CreateInvalidation` on the distribution |
| `CLOUDFRONT_DISTRIBUTION_ID` | The CloudFront distribution ID (`pulumi stack output distributionId`) |

Apt repo infrastructure (S3, CloudFront, signing key) is provisioned by Pulumi in the `platrol-panel` repo at `pulumi/aptrepo/`.

### Local development

To build locally inside Docker (recommended — keeps your laptop's JDK out of it):

```sh
docker run --rm -v "$(pwd):/src" --workdir /src ubuntu:24.04 bash -c "
  apt-get update &&
  apt-get install -y dpkg-dev debhelper openjdk-17-jdk genisoimage maven \
                     lsb-release devscripts python3-setuptools git &&
  ./packaging/build-deb.sh --brand platoform --use-timestamp
"
```

Output lands in `dist/`. Publish manually via `platrol-panel/pulumi/aptrepo/Makefile`'s `make add`.

### Don't

- Don't push to `origin` (Apache upstream)
- Don't change the `99` in the POM version unless you're forking for a new upstream major
- Don't rebase published branches (`4.22`, `4.22-testing`) — always use merges
- Don't cherry-pick onto `4.22` directly — always land on `4.22-testing` first, validate, then merge

---

## Project Overview

Apache CloudStack is an open-source Infrastructure as a Service (IaaS) cloud orchestration platform. It's a Java-based, multi-module Maven project with a modern Vue.js UI, designed to deploy and manage large networks of virtual machines across multiple hypervisors.

## Common Development Commands

### Maven Build Commands
- **Full build**: `mvn clean install`
- **Developer build**: `mvn -P developer clean install`
- **Fast build (skip tests)**: `mvn -P developer -DskipTests=true clean install -T$(nproc)`
- **Simulator build**: `mvn -P developer,systemvm -Dsimulator clean install`
- **VMware build**: `mvn -P developer,systemvm -Dnoredist clean install`

### UI Development Commands (from `ui/` directory)
- **Install dependencies**: `npm install`
- **Development server**: `npm run serve`
- **Build for production**: `npm run build`
- **Lint code**: `npm run lint`
- **Run unit tests**: `npm run test:unit`

### Testing Commands
- **Unit tests**: `mvn test`
- **Integration tests**: `mvn verify`
- **Code style check**: `mvn checkstyle:check`
- **Static analysis**: `mvn -P enablefindbugs compile`

### Database Setup (Development)
- **Deploy database**: `mvn -Pdeveloper -pl developer -Ddeploydb`
- **Deploy simulator database**: `mvn -Pdeveloper -pl developer -Ddeploydb-simulator`

## Architecture Overview

CloudStack follows a **modular, layered architecture** with clear separation of concerns:

### Core Components
- **`api/`** - API definitions, contracts, and command classes
- **`engine/`** - Core orchestration engine with sub-modules:
  - `orchestration/` - VM lifecycle management
  - `storage/` - Storage subsystem with pluggable backends
  - `schema/` - Database schema and DAOs
  - `api/` - API processing and business logic
- **`framework/`** - Cross-cutting infrastructure:
  - `config/` - Configuration management
  - `db/` - ORM and transaction management
  - `jobs/` - Asynchronous job processing
  - `security/` - Security utilities
  - `events/` - Event bus and messaging
- **`plugins/`** - Extensible plugin architecture:
  - `hypervisors/` - KVM, VMware, XenServer, etc.
  - `storage/` - Various storage providers
  - `network-elements/` - Load balancers, firewalls, SDN
  - `user-authenticators/` - LDAP, SAML2, OAuth2
- **`server/`** - Management server implementation
- **`agent/`** - Host agent components
- **`services/`** - System services (console proxy, secondary storage)
- **`ui/`** - Vue.js-based modern web interface

### Key Architectural Patterns
- **Service-Oriented Architecture**: Clear service boundaries with interface contracts
- **Plugin Architecture**: Extensible providers through standardized interfaces
- **Event-Driven Architecture**: Asynchronous processing via event bus
- **Dependency Injection**: Spring Framework for component wiring
- **Repository Pattern**: DAO layer abstracts database operations

### API Design
- **Command Pattern**: Each API operation is a command class
- **Asynchronous Jobs**: Long-running operations via job queue
- **Multi-tenancy**: Domain and account isolation
- **Role-based Access Control**: Fine-grained permissions

## Development Environment Requirements

### Required Tools
- **Java**: OpenJDK 11 (specified in `pom.xml` as `cs.jdk.version`)
- **Maven**: 3.6.0+ for building Java components
- **Node.js**: LTS version for UI development
- **MySQL**: For database development and testing
- **Python**: 3.x for tooling and integration tests

### Code Quality
- Uses **Checkstyle** for code style enforcement
- **SpotBugs** for static analysis
- **PMD** for code analysis
- **Pre-commit hooks** for automated checking

## Important Development Notes

### Module Dependencies
- The project uses a multi-module Maven structure - changes in core modules may affect dependent modules
- Plugin modules depend on engine and framework APIs
- UI is a separate Node.js application that communicates with the REST API

### Database Schema
- Database schema managed in `engine/schema/`
- Migration scripts handle version upgrades
- Use developer profile for local database setup

### Plugin Development
- New hypervisor/storage/network plugins should follow existing patterns in `plugins/`
- Implement required interfaces and register via Spring configuration
- Each plugin type has specific integration points
- For detailed plugin development guide, see [PLUGIN_DEVELOPMENT.md](PLUGIN_DEVELOPMENT.md)

### Testing Strategy
- Unit tests for individual components
- Integration tests using simulator hypervisor
- Marvin framework for end-to-end API testing
- UI has separate Jest-based testing

### Release Process
- Bug fixes go to release branches, then forward-merge to main
- New features only in main branch
- Update `PendingReleaseNotes` for significant changes

## Network Architecture Overview

### Bridge Architecture
CloudStack creates VLAN-aware bridge interfaces for public networks:
- **Bridge naming**: `br<PIF>-<VLAN_ID>` (e.g., `brenp4s0f1-101` for PIF `enp4s0f1` with VLAN ID `101`)
- **Implementation**: `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/BridgeVifDriver.java:277-298`
- **Script execution**: Bridge creation handled by `scripts/vm/network/vnet/modifyvlan.sh`

### Network Bridge IP Assignment
**Important**: CloudStack does **NOT** assign public IPs directly to bridge interfaces. The networking model follows:
- **Layer 2 Bridge Function**: Bridges operate as pure Layer 2 switching infrastructure
- **Interface-based IP Assignment**: Public IPs are assigned to VM NICs that connect to bridges
- **Network Flow**: Public IPs → VM NICs → Bridges (switching only)

#### Key Files:
- **Public IP allocation**: `server/src/main/java/com/cloud/network/guru/PublicNetworkGuru.java:132`
- **IP assignment logic**: `server/src/main/java/com/cloud/network/IpAddressManagerImpl.java`
- **Bridge management**: `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/BridgeVifDriver.java`

### Network Guru System
CloudStack uses "NetworkGuru" pattern for traffic type handling:
- **PublicNetworkGuru**: Handles public traffic IP allocation
- **GuestNetworkGuru**: Manages guest network bridges
- **Multiple implementations**: Different gurus for different network types and SDN solutions

### VIF Driver Architecture
Different hypervisors use specific VIF drivers for bridge management:
- **KVM**: `BridgeVifDriver.java`, `OvsVifDriver.java`, `IvsVifDriver.java`
- **XenServer**: `CitrixResourceBase.java` with bridge integration
- **VMware**: Uses different networking model via distributed switches

### Important Constraints
- **No native bridge IP assignment**: Feature not supported in current architecture
- **Layer 2 focus**: Bridges designed for switching, not routing
- **Modification requirements**: Adding bridge IP assignment would require significant architectural changes

For detailed network configuration, troubleshooting, and advanced networking features, see [NETWORK_ARCHITECTURE.md](NETWORK_ARCHITECTURE.md)

## Encrypted Volume Migration

CloudStack blocks migration of encrypted volumes between storage pools by default. This restriction exists in `VolumeApiServiceImpl.java:3372-3374` where it checks for `passphrase_id != null`. Here's the procedure to safely migrate encrypted volumes:

### Prerequisites
- Stop the VM using the encrypted volume
- Ensure sufficient disk space on source storage for temporary copy
- Database backup (volumes table) before making changes

### Migration Process

#### Step 1: Create Volumes Table Backup
```bash
echo "CREATE TABLE volumes_backup_$(date +%Y%m%d_%H%M%S) AS SELECT * FROM volumes;" | make mysql
```

#### Step 2: Decrypt CloudStack Passphrase
Get the encrypted passphrase from database and decrypt it using CloudStack's encryption utility:
```bash
# Get encrypted passphrase from database (replace VOLUME_ID)
ENCRYPTED_PASSPHRASE=$(echo "SELECT passphrase FROM passphrase WHERE id = (SELECT passphrase_id FROM volumes WHERE id = VOLUME_ID);" | make mysql | tail -1)

# Decrypt using CloudStack utility (4.18+)
java -classpath /usr/share/cloudstack-common/lib/cloudstack-utils.jar \
  com.cloud.utils.crypt.EncryptionCLI -d -e V2 -p password -i "$ENCRYPTED_PASSPHRASE" -v
```

#### Step 3: Create Unencrypted Copy
Use the decrypted passphrase to create an unencrypted copy of the volume:
```bash
# Replace DECRYPTED_PASSPHRASE with output from step 2
# Replace SOURCE_FILE with encrypted volume path
# Replace TARGET_FILE with new unencrypted volume filename
sudo qemu-img convert \
  --object secret,id=sec0,data='DECRYPTED_PASSPHRASE' \
  --image-opts driver=qcow2,encrypt.key-secret=sec0,file.filename=SOURCE_FILE \
  -O qcow2 TARGET_FILE
```

#### Step 4: Update Database
Point the volume record to the unencrypted copy and clear encryption flags:
```sql
UPDATE volumes
SET path = 'NEW_UNENCRYPTED_FILENAME',
    passphrase_id = NULL,
    encrypt_format = NULL,
    updated = NOW()
WHERE id = VOLUME_ID;
```

#### Step 5: Verify and Migrate
1. Start VM to verify it boots with unencrypted volume
2. Once confirmed, use CloudStack's built-in volume migration to move to target storage pool
3. CloudStack will now allow the migration since volume appears unencrypted

### Key Notes
- **Encryption formats**: CloudStack uses LUKS encryption within QCOW2 files
- **Passphrase storage**: Passphrases are encrypted in database using CloudStack's V2 encryption
- **File preservation**: The unencrypted copy preserves backing file references and filesystem data
- **Rollback**: Keep original encrypted files and database backup until migration confirmed successful

### Supported Storage Types
- **PowerFlex/ScaleIO**: Natively supports encrypted volume migration
- **StorPool**: Explicitly blocks encrypted volume migration
- **NFS/Local**: Can be migrated using this procedure
- **Linstor**: Can be target after decryption procedure

This procedure bypasses CloudStack's encryption check by creating an unencrypted copy while preserving data integrity.

## Configuration and Troubleshooting

### Database Properties System (`db.properties`)
CloudStack uses a centralized configuration file for core system settings:
- **Location**: `/etc/cloudstack/management/db.properties` (production) or `utils/conf/db.properties` (development)
- **Loading mechanism**: `utils/src/main/java/com/cloud/utils/db/DbProperties.java`

#### Critical Configuration Properties:
- **`cluster.node.IP`**: Sets the IP address for cluster communication (default: `127.0.0.1`)
  - **Constraint**: Must be a valid local IP address bound to the server
  - **Cannot use**: Hostnames, FQDNs, or non-local IP addresses
- **`cluster.servlet.port`**: Cluster service port (default: `9090`)
- **Database settings**: Connection parameters for MySQL/MariaDB

### Cluster Service Architecture
CloudStack management nodes communicate via HTTPS REST API for clustering:
- **Implementation**: `framework/cluster/src/main/java/com/cloud/cluster/ClusterServiceServletAdapter.java`
- **URL pattern**: `https://[cluster.node.IP]:[cluster.servlet.port]/clusterservice`
- **SSL requirements**: Cluster node IP must match SSL certificate Subject Alternative Names (SANs)

#### Common SSL Certificate Issues:
- **Symptom**: `SSLPeerUnverifiedException: Certificate for <IP> doesn't match any of the subject alternative names`
- **Root cause**: `cluster.node.IP` set to `127.0.0.1` but certificate doesn't include localhost
- **Solution**: Change `cluster.node.IP` to hostname/IP included in certificate SANs

### Configuration Hierarchy and Defaults
CloudStack follows a layered configuration approach:
1. **Hard-coded defaults** in Java source (fallback)
2. **Database properties** (`db.properties`) - system level
3. **Database configuration table** - runtime changeable via API
4. **Per-component configuration** - specific settings

### Common Troubleshooting Approaches

#### 1. Configuration Source Tracing
When troubleshooting configuration issues:
- **Search pattern**: `grep -r "property.name" --include="*.java"` to find where property is read
- **Key files**: Look for `configure()` methods and `@ConfigKey` annotations
- **Defaults**: Check for hardcoded fallback values in source code

#### 2. SSL/TLS Certificate Issues
For SSL-related errors:
- **Check certificate SANs**: `openssl x509 -in cert.pem -text -noout | grep -A1 "Subject Alternative Name"`
- **Validate hostname matching**: Ensure all configured hostnames/IPs are in certificate
- **Common locations**: Certificate configuration often in CA framework (`framework/ca/`)

#### 3. Cluster Communication Debugging
For cluster-related issues:
- **Log pattern**: `ClusterServiceServletImpl` and `ClusterManagerImpl` classes
- **Network validation**: Verify `cluster.node.IP` is reachable from other management nodes
- **Port accessibility**: Ensure `cluster.servlet.port` (default 9090) is open between nodes

#### Service Adapter Pattern
CloudStack uses adapter pattern for pluggable services:
- **Example**: `ClusterServiceAdapter` with `ClusterServiceServletAdapter` implementation
- **Configuration**: Adapters configured via dependency injection
- **Extensibility**: Multiple implementations can be registered for different behaviors

## CloudStack Communication Ports

### Standard Port Assignments
CloudStack uses specific ports for different types of communication:

| Port | Service | Direction | Purpose | Configuration Location |
|------|---------|-----------|---------|----------------------|
| **8080** | Management UI/API | Client → Management | Web UI and REST API | Management server |
| **8250** | Agent Communication | Agent → Management | Host agents connect to management server | `agent.properties:46` |
| **9090** | Cluster Service | Management ↔ Management | Inter-management node communication | `db.properties:24` |
| **443** | Console Proxy | Client → Console Proxy | VM console access | `agent.properties:725` |

### Communication Flow Patterns

#### Agent-Management Communication (Port 8250)
- **Initiator**: CloudStack agents on hypervisor hosts
- **Target**: Management server
- **Protocol**: HTTPS with custom protocol
- **Purpose**: Agent registration, heartbeat, command execution, status reporting
- **Configuration**: `host` and `port` properties in agent.properties

#### Management Cluster Communication (Port 9090)
- **Initiator**: Management server nodes
- **Target**: Other management server nodes
- **Protocol**: HTTPS REST API
- **URL Pattern**: `https://[cluster.node.IP]:[cluster.servlet.port]/clusterservice`
- **Purpose**: Cluster coordination, distributed state management, load balancing
- **Key Configuration**: `cluster.node.IP` in db.properties

#### Client API Communication (Port 8080)
- **Initiator**: Web UI, CLI tools, API clients
- **Target**: Management server
- **Protocol**: HTTP/HTTPS REST API
- **Purpose**: Administrative operations and user interface

### Common Network Troubleshooting

#### Agent Connection Issues
- **Check connectivity**: `telnet [management-server] 8250`
- **Agent logs**: Look for connection errors to management server
- **Management logs**: Check for agent registration failures
- **Configuration**: Verify `host` property in agent.properties points to correct management server

#### Cluster Communication Issues
- **SSL certificate verification**: Ensure `cluster.node.IP` matches certificate SANs
- **Port accessibility**: Verify port 9090 is open between management nodes
- **Log pattern**: Search for `ClusterServiceServletImpl` errors
- **Configuration**: Check `cluster.node.IP` is a valid local IP address

#### Port Conflicts and Firewall Rules
- **Management server**: Requires incoming ports 8080 (UI/API) and 8250 (agents)
- **Cluster setup**: Additional port 9090 for inter-node communication
- **Agent hosts**: Outbound access to management server on port 8250
- **Console access**: Port 443 for console proxy service

### Service Discovery and Configuration
- **Agent configuration**: Agents discover management server via `host` property
- **Cluster discovery**: Management nodes discover peers via database records
- **Load balancing**: Agents can be configured with multiple management server IPs
- **Failover**: Built-in retry logic for agent-management communication

## Additional Documentation

For more detailed information on specific topics, see the following documents:

- **Plugin Development**: [PLUGIN_DEVELOPMENT.md](PLUGIN_DEVELOPMENT.md) - Comprehensive guide for developing custom plugins
- **Resource Management**: [RESOURCE_MANAGEMENT.md](RESOURCE_MANAGEMENT.md) - Resource quotas, limits, and capacity management
- **Network Architecture**: [NETWORK_ARCHITECTURE.md](NETWORK_ARCHITECTURE.md) - Network configuration, redundancy, and troubleshooting
- **Virtual Router Management**: [VR_MANAGEMENT.md](VR_MANAGEMENT.md) - VR customization, iptables management, and advanced configurations
