# Website hosting

## Decision

Publish the engine website from the repository to GitHub Pages, keeping the custom domain and the install and security paths. Package repositories remain separately hosted. The Pages workflow builds and checks pull requests and deploys only the default branch. A build stamp names the revision and build time; it is not a claim that the content was reviewed at that time.

## Consequences

Pages does not provide the previous site's custom response-header policy. The pages use a meta content security policy, but it cannot express `frame-ancestors` and does not cover responses that lack the meta tag. The prior host's HSTS policy is not configured by this repository on Pages. This is a reduction in control over response headers. Signatures and independently verified key fingerprints remain necessary for package authenticity.

The release public key is retained at `/install/unidrive-releases.gpg`; its bytes must be compared with the current published key before cutover. The security contact is the project's private reporting form, consistent with the engine security policy. Its `Expires` field must be renewed before it expires.

The site uses root-relative links and must be served at the custom domain's root. The default repository Pages URL includes a repository path and is not a supported preview: its navigation and assets would point outside the site. Configure the custom domain before the first deployment. For local review, run the build and checker from the repository root, then serve the output as the web root with `python -m http.server --directory site-build --bind 127.0.0.1`.

Before changing DNS, verify the Pages custom domain and ownership, lower the TTL, and configure the Pages deployment source. After the certificate is issued, enforce HTTPS and verify the home page, install page, security contact, key bytes and key fingerprint on the custom domain. Keep the package hosts pointed at their existing service. Only after DNS caches have expired and the new site is verified should the old web service and its domain-specific routes be removed. Keep a rollback path. Clean-machine apt and dnf installation checks must wait until signed packages and repository metadata exist.
