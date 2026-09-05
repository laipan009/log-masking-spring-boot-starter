# Initial GitHub publication

## Start with one commit

Pushing an ordinary preparation branch also publishes all its ancestor commits. Deleted files,
old package names, internal configuration and commit metadata remain readable in that history.
Amending the latest commit does not remove those ancestors.

Use a separate repository containing only the reviewed current tree. From the source repository,
after committing all intended changes:

```sh
python3 scripts/export-public.py ../log-masking-spring-boot-starter-public
```

The script requires a new destination outside the source repository. It copies only regular
files tracked in `HEAD`, initializes `main`, and creates one root commit. It verifies the exported
Git tree matches the source tree exactly. It does not copy old commits, tags, branches, remotes,
reflogs, ignored files or untracked files. The public author is `laipan009` with a GitHub noreply
address; confirm this address in your GitHub email settings if you need account attribution.

The source tree must already be reviewed: exporting removes history, not sensitive content
that is still present in tracked files. The original repository remains available locally.
Do not later merge the original repository's history into this new repository.

In the exported directory:

```sh
cd ../log-masking-spring-boot-starter-public
git log --oneline --all
git rev-list --count HEAD
mvn clean verify
```

The count must be `1`. Create an **empty public repository** named
`laipan009/log-masking-spring-boot-starter` on GitHub. Do not initialize a README, license or
.gitignore there because the local tree already contains them.

Connect and push only the new main branch:

```sh
git remote add origin https://github.com/laipan009/log-masking-spring-boot-starter.git
git push -u origin main
```

Run these commands from the exported directory, not the original preparation checkout.
No force push, mirror push or old tags are needed. This publishes source; it does not publish
a Maven Central artifact or create a release. See [releasing.md](releasing.md) for alpha releases.

## Who can contribute and who can merge

Public visibility lets other people view the project, fork it and propose pull requests. It
does not give strangers write access. Keep `laipan009` as the only account with write/admin
access if you want all merge decisions to remain yours.

`.github/CODEOWNERS` assigns every file, including workflows and CODEOWNERS itself, to
`@laipan009`. This file requests your review; **GitHub branch protection must also require
code-owner approval** to enforce it. CI only runs checks and does not grant merge permission.

After the initial push and first CI run, open **Settings > Branches**, add a classic branch
protection rule for `main`, and configure:

- Require a pull request before merging, with one approving review.
- Require review from Code Owners.
- Dismiss stale approvals when new commits are pushed.
- Require status checks: select `verify` from GitHub Actions, and require an up-to-date branch.
- Require conversation resolution and linear history.
- Leave force pushes and branch deletion disabled.

For a sole maintainer, leave **Do not allow bypassing the above settings** unchecked.
GitHub does not allow approving your own PR. Owner/admin bypass lets you merge your own work
manually after checking CI. It also means the owner can bypass the other protections: keep
admin access limited to yourself. External contributions should go through your explicit
review and normal merge. With an independent maintainer later, revisit admin enforcement.

Use squash merges for a simple main history; disable automatic merging. The checked-in
`.github/branch-protection.json` is an API request body for these settings, not an automatically
applied GitHub configuration. With GitHub CLI installed and authenticated, apply it from this
repository after `main` and the `verify` check exist:

```sh
gh api --method PUT repos/laipan009/log-masking-spring-boot-starter/branches/main/protection \
  --input .github/branch-protection.json
```

This replaces the classic protection settings for `main`; use it for initial setup and review
existing settings before reapplying it later. In the UI, select GitHub Actions as the expected
source for the required check. Verify the resulting settings on GitHub.

## Approve external CI runs separately

In **Settings > Actions > General**:

- Require approval for **all external contributors** to fork pull request workflows.
- Set workflow token permissions to read-only and keep permission to create or approve pull
  requests disabled.
- Allow only the actions needed by this project. The workflow uses GitHub's checkout and
  setup-java actions pinned to full commit SHAs.

Equivalent commands for the first two settings:

```sh
gh api --method PUT repos/laipan009/log-masking-spring-boot-starter/actions/permissions/fork-pr-contributor-approval \
  -f approval_policy=all_external_contributors
gh api --method PUT repos/laipan009/log-masking-spring-boot-starter/actions/permissions/workflow \
  -f default_workflow_permissions=read -F can_approve_pull_request_reviews=false
```

Approving a workflow permits running proposed code; it is separate from approving a merge.
Inspect changes before approving CI, especially workflow changes. The current workflow uses
`pull_request`, read-only permissions, ephemeral GitHub-hosted runners and no deployment
secrets. It does not publish artifacts or merge changes. Keep external PR jobs away from
corporate/self-hosted runners and credentials.

## Verify setup

Check the `main` protection rule, code-owner recognition, the successful `verify` check and
the external-workflow approval policy in GitHub. Local files alone do not prove these
server-side settings are enabled.

References: [branch protection](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches),
[code owners](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners),
[workflow approval](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/approve-runs-from-forks).
