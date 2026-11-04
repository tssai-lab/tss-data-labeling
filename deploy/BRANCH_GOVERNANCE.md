# Backend Branch Governance

## Branch roles

| Branch | Role | Image | Automatic deployment |
|---|---|---|---|
| `backend-ops` | Integration and infrastructure validation | Immutable Git SHA | Main integration node |
| `backend` | Stable backend candidate | Immutable Git SHA | Disabled until route acceptance |
| `main` | Existing repository default and frontend history | Unchanged by this route | Existing frontend workflow only |

The historical `ops/poc-automation` branch is not an active CI or deployment
source.

## Required repository rules

Apply these rules to `backend` before it becomes a production release source:

1. Require a pull request and at least one approval.
2. Require `Backend CI and Image / verify-and-build` to pass.
3. Require the branch to be up to date before merge.
4. Block force pushes and branch deletion.
5. Restrict direct pushes to designated maintainers or a release identity.
6. Resolve review conversations before merge.

Apply the same rules to `backend-ops` when more than one engineer begins using
the integration environment. Until then, every direct push must still have a
recorded commit, green CI result and Main smoke-test record.

## Promotion rule

Promote the already built immutable image; do not rebuild it on the target
branch or node. The promotion record must contain the source commit, image
digest, target environment, approver, smoke result and rollback SHA.
