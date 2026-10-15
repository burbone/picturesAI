interface ImageCardProps {
  thumbnailUrl: string | null;
  imageUrl: string | null;
  prompt: string;
  status?: string;
  tags?: string[];
  onClick?: () => void;
}

export default function ImageCard({
  thumbnailUrl,
  imageUrl,
  prompt,
  status,
  tags,
  onClick,
}: ImageCardProps) {
  const src = thumbnailUrl || imageUrl || "";
  const handleClick = () => {
    if (onClick) {
      onClick();
      return;
    }
    if (imageUrl) window.open(imageUrl, "_blank", "noopener,noreferrer");
  };

  return (
    <div className="card">
      {src ? (
        <img src={src} alt={prompt} onClick={handleClick} loading="lazy" />
      ) : (
        <div className="card-placeholder" onClick={handleClick}>
          <span className="muted">Нет изображения</span>
        </div>
      )}
      <div className="body">
        <div className="prompt">{prompt}</div>
        {status && <span className={`status ${status}`}>{status}</span>}
        {tags && tags.length > 0 && (
          <div className="tags">
            {tags.slice(0, 4).map((t) => (
              <span className="tag" key={t}>
                {t}
              </span>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}